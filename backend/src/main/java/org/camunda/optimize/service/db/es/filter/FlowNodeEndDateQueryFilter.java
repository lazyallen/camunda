/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.es.filter;

import static org.camunda.optimize.service.db.es.filter.util.ModelElementFilterQueryUtil.createFlowNodeEndDateFilterQuery;
import static org.camunda.optimize.service.db.schema.index.ProcessInstanceIndex.FLOW_NODE_INSTANCES;
import static org.elasticsearch.index.query.QueryBuilders.nestedQuery;

import java.util.List;
import org.apache.lucene.search.join.ScoreMode;
import org.camunda.optimize.dto.optimize.query.report.single.filter.data.date.flownode.FlowNodeDateFilterDataDto;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.springframework.stereotype.Component;

@Component
public class FlowNodeEndDateQueryFilter implements QueryFilter<FlowNodeDateFilterDataDto<?>> {
  @Override
  public void addFilters(
      final BoolQueryBuilder query,
      final List<FlowNodeDateFilterDataDto<?>> flowNodeEndDateFilters,
      final FilterContext filterContext) {
    final List<QueryBuilder> filters = query.filter();
    for (FlowNodeDateFilterDataDto<?> flowNodeEndDateFilter : flowNodeEndDateFilters) {
      filters.add(
          nestedQuery(
              FLOW_NODE_INSTANCES,
              createFlowNodeEndDateFilterQuery(flowNodeEndDateFilter, filterContext.getTimezone()),
              ScoreMode.None));
    }
  }
}
