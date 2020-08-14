/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.report.process.single.flownode.frequency.groupby.date.distributed_by.flownode;

import org.camunda.optimize.dto.optimize.query.report.single.process.group.ProcessGroupByType;
import org.camunda.optimize.rest.engine.dto.ProcessInstanceEngineDto;
import org.camunda.optimize.test.util.ProcessReportDataType;

import java.time.OffsetDateTime;
import java.util.Map;

public class FlowNodeFrequencyByFlowNodeEndDateByFlowNodeReportEvaluationIT
  extends FlowNodeFrequencyByFlowNodeDateByFlowNodeReportEvaluationIT {

  @Override
  protected ProcessReportDataType getReportDataType() {
    return ProcessReportDataType.FLOW_NODE_FREQUENCY_GROUP_BY_FLOW_NODE_END_DATE_BY_FLOW_NODE;
  }

  @Override
  protected ProcessGroupByType getGroupByType() {
    return ProcessGroupByType.END_DATE;
  }

  @Override
  protected void changeModelElementDates(final Map<String, OffsetDateTime> updates) {
    engineDatabaseExtension.changeActivityInstanceEndDates(updates);
  }

  @Override
  protected void changeModelElementDate(final ProcessInstanceEngineDto processInstance,
                                        final String ActivityInstanceId,
                                        final OffsetDateTime dateToChangeTo) {
    engineDatabaseExtension.changeActivityInstanceEndDate(processInstance.getId(), ActivityInstanceId, dateToChangeTo);
  }
}
