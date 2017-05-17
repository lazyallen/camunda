package org.camunda.tngp.transport.impl;

import static org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor.HEADER_LENGTH;
import static org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor.TYPE_MESSAGE;
import static org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor.alignedLength;
import static org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor.lengthOffset;
import static org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor.typeOffset;
import static org.camunda.tngp.transport.impl.StaticControlFrames.CLOSE_FRAME;
import static org.camunda.tngp.transport.impl.StaticControlFrames.END_OF_STREAM_FRAME;
import static org.camunda.tngp.transport.impl.TransportControlFrameDescriptor.TYPE_CONTROL_CLOSE;
import static org.camunda.tngp.transport.impl.TransportControlFrameDescriptor.TYPE_CONTROL_END_OF_STREAM;
import static org.camunda.tngp.transport.impl.TransportControlFrameDescriptor.TYPE_CONTROL_KEEP_ALIVE;
import static org.camunda.tngp.transport.impl.TransportControlFrameDescriptor.TYPE_PROTO_CONTROL_FRAME;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.agrona.DirectBuffer;
import org.agrona.LangUtil;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.camunda.tngp.dispatcher.impl.log.DataFrameDescriptor;
import org.camunda.tngp.transport.Channel;
import org.camunda.tngp.transport.PooledFuture;
import org.camunda.tngp.transport.SocketAddress;
import org.camunda.tngp.transport.spi.TransportChannelHandler;
import org.camunda.tngp.transport.util.IntObjectBiConsumer;
import org.camunda.tngp.transport.util.SharedStateMachine;
import org.camunda.tngp.transport.util.SharedStateMachineBlueprint;
import org.camunda.tngp.util.time.ClockUtil;

public class ChannelImpl implements Channel
{

    protected static final int INITIAL_CONTROL_FRAME_BUFFER_SIZE = 32;

    // states
    public static final int STATE_CONNECTING = 1 << 0;
    public static final int STATE_CONNECTED = 1 << 1;
    // ready to use, i.e. connected and successfully registered with sender and receiver
    public static final int STATE_READY = 1 << 6;
    public static final int STATE_CLOSE_INITIATED = 1 << 2;
    public static final int STATE_CLOSE_RECEIVED = 1 << 3;
    public static final int STATE_CLOSED = 1 << 4;
    public static final int STATE_CLOSED_UNEXPECTEDLY = 1 << 5;

    // state management
    protected final SharedStateMachine<ChannelImpl> stateMachine;

    // channel
    protected SocketChannel media;
    protected final ByteBuffer channelReadBuffer;
    protected final UnsafeBuffer channelReadBufferView;
    protected final int streamId;
    protected final SocketAddress remoteAddress;
    protected final TransportChannelHandler handler;
    protected final ManyToOneRingBuffer controlFramesBuffer;
    protected ByteBuffer currentControlFrameBuffer = ByteBuffer.allocate(INITIAL_CONTROL_FRAME_BUFFER_SIZE);

    // pooling
    protected AtomicLong lastUsed = new AtomicLong(Long.MIN_VALUE);
    protected AtomicInteger references = new AtomicInteger(0);
    protected long lastKeepAlive;

    /**
     * creates a not yet connected channel (in case of client)
     */
    public ChannelImpl(
            int streamId,
            SocketAddress remoteAddress,
            int maxMessageLength,
            ManyToOneRingBuffer controlFrameBuffer,
            TransportChannelHandler handler,
            SharedStateMachineBlueprint<ChannelImpl> stateMachineLifecycle)
    {
        this.controlFramesBuffer = controlFrameBuffer;
        this.streamId = streamId;
        this.remoteAddress = remoteAddress;
        this.handler = handler;

        this.channelReadBuffer = ByteBuffer.allocateDirect(maxMessageLength * 2);
        this.channelReadBufferView = new UnsafeBuffer(channelReadBuffer);

        this.stateMachine = stateMachineLifecycle.buildStateMachine(streamId, this);

        stateMachine.makeStateTransition(STATE_CONNECTING);
    }

    /**
     * creates a channel from a connected socket channel (in case of server)
     */
    public ChannelImpl(
            int streamId,
            SocketAddress remoteAddress,
            final SocketChannel media,
            int maxMessageLength,
            ManyToOneRingBuffer controlFrameBuffer,
            TransportChannelHandler handler,
            SharedStateMachineBlueprint<ChannelImpl> stateMachineLifecycle)
    {
        this(streamId, remoteAddress, maxMessageLength, controlFrameBuffer, handler, stateMachineLifecycle);
        this.media = media;
        stateMachine.makeStateTransition(STATE_CONNECTING, STATE_CONNECTED);
    }

    // channel interaction

    public boolean scheduleControlFrame(DirectBuffer frame)
    {
        return scheduleControlFrame(frame, 0, frame.capacity());
    }

    public boolean scheduleControlFrame(DirectBuffer frame, int offset, int length)
    {
        return controlFramesBuffer.write(streamId, frame, offset, length);
    }

    /**
     * @return true if control frame could be completely written to socket channel
     */
    public boolean trySendControlFrame()
    {
        try
        {
            media.write(currentControlFrameBuffer);
            return !currentControlFrameBuffer.hasRemaining();
        }
        catch (IOException e)
        {
            e.printStackTrace();
            closeUnexpectedly();
            return false;
        }
    }

    public void initControlFrame(DirectBuffer buf, int offset, int length)
    {
        currentControlFrameBuffer.clear();
        if (length > currentControlFrameBuffer.capacity())
        {
            currentControlFrameBuffer = ByteBuffer.allocate(length);
        }

        buf.getBytes(offset, currentControlFrameBuffer, length);
        currentControlFrameBuffer.flip();
    }

    protected static boolean isProtocolMessage(final int msgType)
    {
        return msgType == TYPE_MESSAGE || msgType == TYPE_PROTO_CONTROL_FRAME;
    }

    public int receive()
    {
        final int bytesRead = mediaReceive(channelReadBuffer);
        int available = channelReadBuffer.position();

        if (bytesRead >= 0)
        {
            while (available >= HEADER_LENGTH)
            {
                final int msgLength = channelReadBufferView.getInt(lengthOffset(0));
                final int frameLength = alignedLength(msgLength);

                if (available < frameLength)
                {
                    break;
                }
                else
                {
                    final int msgType = channelReadBufferView.getShort(typeOffset(0));

                    boolean handled = false;

                    if (isProtocolMessage(msgType))
                    {
                        handled = handleProtocolMessage(msgType, msgLength, frameLength);
                    }
                    else
                    {
                        handled = handleGeneralControlFrame(msgType, frameLength);
                    }

                    if (handled)
                    {
                        channelReadBuffer.limit(available);
                        channelReadBuffer.position(frameLength);
                        channelReadBuffer.compact();
                        available -= frameLength;
                    }
                    else
                    {
                        break;
                    }
                }
            }
        }
        else
        {
            // stream closed (either on the other side or unexpectedly)
            if (stateMachine.isInAnyState(STATE_CONNECTED | STATE_READY))
            {
                closeUnexpectedly();
            }
            else
            {
                closeExpectedly();
            }

            mediaClose();
        }

        return bytesRead;
    }

    protected int mediaReceive(ByteBuffer receiveBuffer)
    {
        int bytesReceived = -2;

        try
        {
            bytesReceived = media.read(receiveBuffer);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }

        return bytesReceived;
    }

    /**
     * @param msgType see {@link DataFrameDescriptor}
     * @param messageLength exact message length
     * @param frameLength aligned message length
     */
    protected boolean handleProtocolMessage(int msgType, int messageLength, int frameLength)
    {
        if (msgType == TYPE_MESSAGE)
        {
            return handler.onChannelReceive(this,
                    channelReadBufferView,
                    HEADER_LENGTH,
                    messageLength);
        }
        else
        {
            return handler.onControlFrame(this, channelReadBufferView, 0, frameLength);
        }
    }

    protected boolean handleGeneralControlFrame(final int msgType, final int frameLength)
    {
        if (msgType == TYPE_CONTROL_END_OF_STREAM)
        {
            closeExpectedly();
            return true;
        }
        else if (msgType == TYPE_CONTROL_CLOSE)
        {
            if (stateMachine.makeStateTransition(
                    STATE_READY | STATE_CONNECTED | STATE_CLOSE_INITIATED,
                    STATE_CLOSE_RECEIVED))
            {
                return scheduleControlFrame(END_OF_STREAM_FRAME, 0, END_OF_STREAM_FRAME.capacity());
            }
            else
            {
                return true;
            }
        }
        else if (msgType == TYPE_CONTROL_KEEP_ALIVE)
        {
            handler.onChannelKeepAlive(this);
            return true;
        }
        else
        {
            System.err.println("Received unhandled control frame of type " + msgType);
            return true;
        }
    }

    public void registerSelector(Selector selector, int ops)
    {
        try
        {
            final SelectionKey key = media.register(selector, ops);
            key.attach(this);
        }
        catch (ClosedChannelException e)
        {
            LangUtil.rethrowUnchecked(e);
        }
    }

    public void removeSelector(Selector selector)
    {
        final SelectionKey key = media.keyFor(selector);
        if (key != null)
        {
            key.cancel();
        }
    }

    public int write(ByteBuffer buffer)
    {
        int bytesWritten = -1;

        try
        {
            bytesWritten = media.write(buffer);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            closeUnexpectedly();
        }

        return bytesWritten;
    }

    public void onChannelSendError(DirectBuffer dataBlock, int offset, int length)
    {
        handler.onChannelSendError(this, dataBlock, offset, length);
    }

    protected void closeExpectedly()
    {
        stateMachine.makeStateTransition(STATE_CLOSED);
        mediaClose();
    }

    protected void closeUnexpectedly()
    {
        stateMachine.makeStateTransition(STATE_CLOSED_UNEXPECTEDLY);
        mediaClose();
    }

    protected void mediaClose()
    {
        try
        {
            media.close();
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public SocketChannel getSocketChannel()
    {
        return media;
    }

    // state inspection and interaction

    public void listenFor(int stateMask, IntObjectBiConsumer<ChannelImpl> callback)
    {
        final int currentState = stateMachine.getCurrentState();
        if ((currentState & stateMask) != 0)
        {
            callback.accept(currentState, this);
        }
        else
        {
            stateMachine.listenFor(stateMask, callback);
        }
    }

    public void listenForClose(CompletableFuture<ChannelImpl> future)
    {
        if (isClosed())
        {
            future.complete(this);
        }
        else
        {
            stateMachine.listenFor(STATE_CLOSED, -1, future);
        }
    }

    public void listenForReady(PooledFuture<Channel> future)
    {
        if (isReady())
        {
            future.resolve(this);
        }
        else
        {
            stateMachine.listenFor(STATE_READY, STATE_CLOSED | STATE_CLOSED_UNEXPECTEDLY, future);
        }
    }

    public void setConnected()
    {
        stateMachine.makeStateTransition(STATE_CONNECTING, STATE_CONNECTED);
    }

    public boolean isConnected()
    {
        return stateMachine.isInState(STATE_CONNECTED);
    }

    public boolean isClosed()
    {
        final int currentState = stateMachine.getCurrentState();
        return currentState == STATE_CLOSED || currentState == STATE_CLOSED_UNEXPECTEDLY;
    }

    public boolean isReady()
    {
        return stateMachine.isInState(STATE_READY);
    }

    public boolean isConnecting()
    {
        return stateMachine.isInState(STATE_CONNECTING);
    }




    public int getStreamId()
    {
        return streamId;
    }

    public SocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }


    public boolean initiateClose()
    {
        final boolean closeInitiated = stateMachine.makeStateTransition(STATE_READY | STATE_CONNECTED, STATE_CLOSE_INITIATED);

        if (closeInitiated)
        {
            final boolean controlFrameScheduled = scheduleControlFrame(CLOSE_FRAME, 0, CLOSE_FRAME.capacity());
            if (!controlFrameScheduled)
            {
                closeUnexpectedly();
            }
        }

        return closeInitiated;
    }

    public boolean startConnect()
    {
        try
        {
            media = SocketChannel.open();
            media.setOption(StandardSocketOptions.TCP_NODELAY, true);
            media.configureBlocking(false);
            media.connect(remoteAddress.toInetSocketAddress());
            return true;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            closeUnexpectedly();
            return false;
        }
    }

    public void finishConnect()
    {
        try
        {
            media.finishConnect();
            stateMachine.makeStateTransition(STATE_CONNECTED);
        }
        catch (IOException e)
        {
            e.printStackTrace();
            closeUnexpectedly();
        }
    }

    public boolean setReady()
    {
        return stateMachine.makeStateTransition(STATE_CONNECTED, STATE_READY);
    }

    public void countUsageBegin()
    {
        references.incrementAndGet();
        lastUsed.set(ClockUtil.getCurrentTimeInMillis());
    }

    public void countUsageEnd()
    {
        references.decrementAndGet();
        lastUsed.set(ClockUtil.getCurrentTimeInMillis());
    }

    public boolean isInUse()
    {
        return references.get() > 0;
    }

    public long getLastUsed()
    {
        return lastUsed.get();
    }

    public long getLastKeepAlive()
    {
        return lastKeepAlive;
    }

    public void setLastKeepAlive(long lastKeepAlive)
    {
        this.lastKeepAlive = lastKeepAlive;
    }

    public TransportChannelHandler getHandler()
    {
        return handler;
    }

    public SharedStateMachine<ChannelImpl> getStateMachine()
    {
        return stateMachine;
    }
}
