/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.engine.processor.workflow.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import io.zeebe.engine.util.EngineRule;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.builder.ServiceTaskBuilder;
import io.zeebe.protocol.record.Assertions;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.IncidentIntent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.protocol.record.intent.WorkflowInstanceIntent;
import io.zeebe.protocol.record.value.BpmnElementType;
import io.zeebe.protocol.record.value.ErrorType;
import io.zeebe.protocol.record.value.IncidentRecordValue;
import io.zeebe.protocol.record.value.WorkflowInstanceRecordValue;
import io.zeebe.test.util.collection.Maps;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.util.function.Consumer;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

public class ServiceTaskIncidentTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();

  private static final String PROCESS_ID = "process";
  private static final String SERVICE_TASK_ID = "task";

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  private static BpmnModelInstance createBPMNModel(final Consumer<ServiceTaskBuilder> consumer) {
    final var builder =
        Bpmn.createExecutableProcess(PROCESS_ID).startEvent().serviceTask(SERVICE_TASK_ID);

    consumer.accept(builder);

    return builder.endEvent().done();
  }

  @Test
  public void shouldCreateIncidentIfJobTypeExpressionEvaluationFailed() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            createBPMNModel(
                t ->
                    t.zeebeJobTypeExpression(
                        "lorem.ipsum"))) // invalid expression, will fail at runtime
        .deploy();

    // when
    final long workflowInstanceKey = ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID).create();

    final Record<WorkflowInstanceRecordValue> recordThatLeadsToIncident =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATING)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .withElementType(BpmnElementType.SERVICE_TASK)
            .getFirst();

    // then
    final Record<IncidentRecordValue> incidentRecord =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    Assertions.assertThat(incidentRecord.getValue())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "failed to evaluate expression 'lorem.ipsum': no variable found for name 'lorem'")
        .hasElementId(SERVICE_TASK_ID)
        .hasElementInstanceKey(recordThatLeadsToIncident.getKey())
        .hasJobKey(-1L)
        .hasVariableScopeKey(recordThatLeadsToIncident.getKey());
  }

  @Test
  public void shouldCreateIncidentIfJobTypeExpressionOfInvalidType() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            createBPMNModel(
                t -> t.zeebeJobTypeExpression("false"))) // boolean expression, has wrong type
        .deploy();

    // when
    final long workflowInstanceKey = ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID).create();

    final Record<WorkflowInstanceRecordValue> recordThatLeadsToIncident =
        RecordingExporter.workflowInstanceRecords(WorkflowInstanceIntent.ELEMENT_ACTIVATING)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .withElementType(BpmnElementType.SERVICE_TASK)
            .getFirst();

    // then
    final Record<IncidentRecordValue> incidentRecord =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    Assertions.assertThat(incidentRecord.getValue())
        .hasErrorType(ErrorType.EXTRACT_VALUE_ERROR)
        .hasErrorMessage(
            "Expected result of the expression 'false' to be 'STRING', but was 'BOOLEAN'.")
        .hasElementId(SERVICE_TASK_ID)
        .hasElementInstanceKey(recordThatLeadsToIncident.getKey())
        .hasJobKey(-1L)
        .hasVariableScopeKey(recordThatLeadsToIncident.getKey());
  }

  @Test
  public void shouldResolveIncidentAfterExpressionEvaluationFailed() {
    // given
    ENGINE
        .deployment()
        .withXmlResource(
            createBPMNModel(
                t -> t.zeebeJobTypeExpression("lorem"))) // invalid expression, will fail at runtime
        .deploy();

    final long workflowInstanceKey = ENGINE.workflowInstance().ofBpmnProcessId(PROCESS_ID).create();

    final Record<IncidentRecordValue> incidentRecord =
        RecordingExporter.incidentRecords(IncidentIntent.CREATED)
            .withWorkflowInstanceKey(workflowInstanceKey)
            .getFirst();

    // when

    // ... update state to resolve issue
    ENGINE
        .variables()
        .ofScope(incidentRecord.getValue().getElementInstanceKey())
        .withDocument(Maps.of(entry("lorem", "order123")))
        .update();

    // ... resolve incident
    final Record<IncidentRecordValue> incidentResolvedEvent =
        ENGINE
            .incident()
            .ofInstance(workflowInstanceKey)
            .withKey(incidentRecord.getKey())
            .resolve();

    // then
    assertThat(
            RecordingExporter.jobRecords(JobIntent.CREATED)
                .withWorkflowInstanceKey(workflowInstanceKey)
                .withElementId(SERVICE_TASK_ID)
                .exists())
        .isTrue();

    assertThat(incidentResolvedEvent.getKey()).isEqualTo(incidentRecord.getKey());
  }
}
