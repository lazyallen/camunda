/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.filter;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.lucene.search.join.ScoreMode;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.data.FlowNodeDurationFiltersDataDto;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;

import static org.camunda.optimize.service.es.filter.util.modelelement.ModelElementFilterQueryUtil.createFlowNodeDurationFilterQuery;
import static org.camunda.optimize.service.es.schema.index.ProcessInstanceIndex.FLOW_NODE_INSTANCES;
import static org.elasticsearch.index.query.QueryBuilders.nestedQuery;

@Component
public class FlowNodeDurationQueryFilter implements QueryFilter<FlowNodeDurationFiltersDataDto> {

  public void addFilters(final BoolQueryBuilder query,
                         final List<FlowNodeDurationFiltersDataDto> durationFilters,
                         final ZoneId timezone, final boolean isUserTaskReport) {
    if (CollectionUtils.isNotEmpty(durationFilters)) {
      final List<QueryBuilder> filters = query.filter();
      durationFilters.forEach(filter -> filters.add(nestedQuery(
        FLOW_NODE_INSTANCES, createFlowNodeDurationFilterQuery(filter), ScoreMode.None)));
    }
  }

}
