/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.usertask;

import io.camunda.zeebe.engine.processing.bpmn.behavior.BpmnBehaviors;
import io.camunda.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedRejectionWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.processing.usertask.processors.UserTaskCommandProcessor;
import io.camunda.zeebe.engine.state.mutable.MutableProcessingState;
import io.camunda.zeebe.protocol.impl.record.value.usertask.UserTaskRecord;
import io.camunda.zeebe.protocol.record.intent.UserTaskIntent;
import io.camunda.zeebe.stream.api.records.TypedRecord;

public class UserTaskProcessor implements TypedRecordProcessor<UserTaskRecord> {

  private final UserTaskCommandProcessors commandProcessors;

  private final TypedRejectionWriter rejectionWriter;
  private final TypedResponseWriter responseWriter;

  public UserTaskProcessor(
      final MutableProcessingState state,
      final BpmnBehaviors bpmnBehaviors,
      final Writers writers) {
    this.commandProcessors = new UserTaskCommandProcessors(state, bpmnBehaviors, writers);

    this.rejectionWriter = writers.rejection();
    this.responseWriter = writers.response();
  }

  @Override
  public void processRecord(final TypedRecord<UserTaskRecord> command) {
    final UserTaskIntent intent = (UserTaskIntent) command.getIntent();

    final var commandProcessor = commandProcessors.getCommandProcessor(intent);
    commandProcessor
        .check(command)
        .ifRightOrLeft(
            persistedRecord -> processRecord(commandProcessor, command, persistedRecord),
            violation -> {
              rejectionWriter.appendRejection(command, violation.getLeft(), violation.getRight());
              responseWriter.writeRejectionOnCommand(
                  command, violation.getLeft(), violation.getRight());
            });
  }

  private void processRecord(
      final UserTaskCommandProcessor processor,
      final TypedRecord<UserTaskRecord> command,
      final UserTaskRecord persistedRecord) {

    processor.onCommand(command, persistedRecord);
    processor.onFinalizeCommand(command, persistedRecord);
  }
}
