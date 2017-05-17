package org.camunda.tngp.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletionException;

import org.camunda.tngp.test.util.TestUtil;
import org.camunda.tngp.transport.TransportBuilder.ThreadingMode;
import org.camunda.tngp.transport.impl.ChannelImpl;
import org.camunda.tngp.util.time.ClockUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ClientChannelPoolingTest
{

    @Rule
    public ExpectedException exception = ExpectedException.none();

    protected Transport clientTransport;
    protected Transport serverTransport;

    @Before
    public void setUp()
    {
        clientTransport = Transports.createTransport("client")
            .threadingMode(ThreadingMode.SHARED)
            .build();

        serverTransport = Transports.createTransport("server")
                .threadingMode(ThreadingMode.SHARED)
                .build();
    }

    @After
    public void tearDown()
    {
        ClockUtil.reset();
        clientTransport.close();
        serverTransport.close();
    }

    @Test
    public void shouldServeClientChannel()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        serverTransport.createServerSocketBinding(addr).bind();

        // when
        final Channel channel = channelManager.requestChannel(addr);

        // then
        assertThat(channel.isReady()).isTrue();
        assertThat(channel.getRemoteAddress()).isEqualTo(addr);
    }

    @Test
    public void shouldServeClientChannelAsync()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        serverTransport.createServerSocketBinding(addr).bind();

        // when
        final PooledFuture<Channel> channelFuture = channelManager.requestChannelAsync(addr);

        // then
        final Channel channel = TestUtil
                .doRepeatedly(() -> channelFuture.poll())
                .until(c -> c != null);
        assertThat(channelFuture.isFailed()).isFalse();

        assertThat(channel.isReady()).isTrue();
        assertThat(channel.getRemoteAddress()).isEqualTo(addr);
    }

    @Test
    public void shouldReuseClientChannelsToSameRemoteAddress()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        serverTransport.createServerSocketBinding(addr).bind();

        final Channel stream1 = channelManager.requestChannel(addr);

        // when
        final Channel stream2 = channelManager.requestChannel(addr);

        // then
        assertThat(stream1).isSameAs(stream2);
    }

    @Test
    public void shouldNotReuseStreamsToDifferentRemoteAddress()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        final SocketAddress addr1 = new SocketAddress("localhost", 51115);
        final SocketAddress addr2 = new SocketAddress("localhost", 51116);
        serverTransport.createServerSocketBinding(addr1).bind();
        serverTransport.createServerSocketBinding(addr2).bind();

        final Channel stream1 = channelManager.requestChannel(addr1);

        // when
        final Channel stream2 = channelManager.requestChannel(addr2);

        // then
        assertThat(stream2).isNotSameAs(stream1);
        assertThat(stream2.getStreamId()).isNotEqualTo(stream1.getStreamId());
    }

    @Test
    public void shouldOpenNewChannelAfterChannelClose()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        serverTransport.createServerSocketBinding(addr).bind();

        final Channel channel1 = channelManager.requestChannel(addr);
        ((ChannelImpl) channel1).initiateClose();
        TestUtil.waitUntil(() -> channel1.isClosed());

        // when
        final Channel channel2 = channelManager.requestChannel(addr);

        // then
        assertThat(channel2).isNotSameAs(channel1);
        assertThat(channel2.getStreamId()).isNotEqualTo(channel1.getStreamId());
    }

    @Test
    public void shouldCloseChannelsOnPoolClose()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        serverTransport.createServerSocketBinding(addr).bind();

        final Channel channel = channelManager.requestChannel(addr);

        // when
        channelManager.closeAllChannelsAsync().join();

        // then
        assertThat(channel.isClosed()).isTrue();
    }

    @Test
    public void shouldEvictUnusedStreamWhenCapacityIsReached()
    {
        // given
        ClockUtil.setCurrentTime(new Date().getTime());

        final int initialCapacity = 2;

        final ChannelManager channelManager = clientTransport
                .createClientChannelPool()
                .initialCapacity(initialCapacity)
                .build();

        bindServerSocketsInPortRange(51115, initialCapacity + 1);
        final Channel[] channels = openStreamsInPortRange(channelManager, 51115, initialCapacity);

        channelManager.returnChannel(channels[1]);
        ClockUtil.addTime(Duration.ofHours(1));
        channelManager.returnChannel(channels[0]);

        // when
        final Channel newChannel = channelManager.requestChannel(new SocketAddress("localhost", 51115 + initialCapacity));

        // then
        // there is no object reuse
        assertThat(channels).doesNotContain(newChannel);

        // the least recently returned channel has been closed asynchronously
        TestUtil.waitUntil(() -> channels[1].isClosed());
        assertThat(channels[1].isClosed()).isTrue();
        assertThat(channels[0].isReady()).isTrue();
    }

    @Test
    public void shouldGrowPoolWhenCapacityIsExceeded()
    {
        final int initialCapacity = 2;

        final ChannelManager channelManager = clientTransport
                .createClientChannelPool()
                .initialCapacity(initialCapacity)
                .build();

        bindServerSocketsInPortRange(51115, initialCapacity + 1);

        // when
        final Channel[] channels = openStreamsInPortRange(channelManager, 51115, initialCapacity + 1);

        // then all channels are open
        assertThat(channels).hasSize(initialCapacity + 1);
        for (Channel channel : channels)
        {
            assertThat(channel.isReady()).isTrue();
        }
    }

    @Test
    public void shouldFailToServeAsyncWhenChannelConnectFails()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();
        final SocketAddress addr = new SocketAddress("localhost", 51115);

        // when
        final PooledFuture<Channel> future = channelManager.requestChannelAsync(addr);

        // then
        TestUtil.waitUntil(() -> future.isFailed());

        assertThat(future.isFailed()).isTrue();
        assertThat(future.poll()).isNull();
    }

    @Test
    public void shouldFailToServeWhenChannelConenctFails()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();
        final SocketAddress addr = new SocketAddress("localhost", 51115);

        // then
        exception.expect(CompletionException.class);

        // when
        channelManager.requestChannel(addr);
    }

    @Test
    public void shouldAllowNullValuesOnReturnChannel()
    {
        // given
        final ChannelManager channelManager = clientTransport.createClientChannelPool().build();

        // when
        try
        {
            channelManager.returnChannel(null);
            // then there is no exception
        }
        catch (Exception e)
        {
            fail("should not throw exception");
        }
    }

    @Test
    public void shouldResetFailedConnectFuturesOnRelease()
    {
        // given
        final ChannelManager channelManager = clientTransport
                .createClientChannelPool()
                .build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        final PooledFuture<Channel> future = channelManager.requestChannelAsync(addr);

        TestUtil.waitUntil(() -> future.isFailed());

        // when
        future.release();

        // then
        assertThat(future.poll()).isNull();
        assertThat(future.isFailed()).isFalse();
    }

    @Test
    public void shouldResetSuccessfulConnectFuturesOnRelease()
    {
        // given
        final ChannelManager channelManager = clientTransport
                .createClientChannelPool()
                .build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);
        serverTransport.createServerSocketBinding(addr).bind();

        final PooledFuture<Channel> future = channelManager.requestChannelAsync(addr);
        TestUtil.waitUntil(() -> future.poll() != null);

        // when
        future.release();

        // then
        assertThat(future.poll()).isNull();
        assertThat(future.isFailed()).isFalse();
    }

    @Test
    public void shouldFailConcurrentRequestsForSameRemoteAddress()
    {
        final ChannelManager channelManager = clientTransport
                .createClientChannelPool()
                .build();

        final SocketAddress addr = new SocketAddress("localhost", 51115);

        // when
        final PooledFuture<Channel> future1 = channelManager.requestChannelAsync(addr);
        final PooledFuture<Channel> future2 = channelManager.requestChannelAsync(addr);
        TestUtil.waitUntil(() -> future1.isFailed() && future2.isFailed());

        // then
        assertThat(future1.isFailed()).isTrue();
        assertThat(future2.isFailed()).isTrue();
    }

    protected Channel[] openStreamsInPortRange(ChannelManager pool, int firstPort, int range)
    {
        final Channel[] channels = new Channel[range];
        for (int i = 0; i < range; i++)
        {
            channels[i] = pool.requestChannel(new SocketAddress("localhost", firstPort + i));
        }
        return channels;
    }

    protected void bindServerSocketsInPortRange(int firstPort, int range)
    {
        for (int i = 0; i < range + 1; i++)
        {
            serverTransport.createServerSocketBinding(new SocketAddress("localhost", firstPort + i)).bind();
        }
    }
}
