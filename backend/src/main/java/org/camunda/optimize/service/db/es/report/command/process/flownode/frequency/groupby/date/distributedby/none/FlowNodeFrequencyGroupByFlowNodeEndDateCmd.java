/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.es.report.command.process.flownode.frequency.groupby.date.distributedby.none;

import java.util.List;
import org.camunda.optimize.dto.optimize.query.report.single.result.hyper.MapResultEntryDto;
import org.camunda.optimize.service.db.es.report.command.ProcessCmd;
import org.camunda.optimize.service.db.es.report.command.exec.ProcessReportCmdExecutionPlan;
import org.camunda.optimize.service.db.es.report.command.exec.builder.ReportCmdExecutionPlanBuilder;
import org.camunda.optimize.service.db.es.report.command.modules.distributed_by.process.ProcessDistributedByNone;
import org.camunda.optimize.service.db.es.report.command.modules.group_by.process.date.ProcessGroupByFlowNodeEndDate;
import org.camunda.optimize.service.db.es.report.command.modules.view.process.frequency.ProcessViewFlowNodeFrequency;
import org.springframework.stereotype.Component;

@Component
public class FlowNodeFrequencyGroupByFlowNodeEndDateCmd
    extends ProcessCmd<List<MapResultEntryDto>> {

  public FlowNodeFrequencyGroupByFlowNodeEndDateCmd(final ReportCmdExecutionPlanBuilder builder) {
    super(builder);
  }

  @Override
  protected ProcessReportCmdExecutionPlan<List<MapResultEntryDto>> buildExecutionPlan(
      final ReportCmdExecutionPlanBuilder builder) {
    return builder
        .createExecutionPlan()
        .processCommand()
        .view(ProcessViewFlowNodeFrequency.class)
        .groupBy(ProcessGroupByFlowNodeEndDate.class)
        .distributedBy(ProcessDistributedByNone.class)
        .resultAsMap()
        .build();
  }
}
