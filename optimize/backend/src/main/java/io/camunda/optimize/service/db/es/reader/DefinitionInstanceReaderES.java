/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.db.es.reader;

import static io.camunda.optimize.service.db.DatabaseConstants.MAX_RESPONSE_SIZE_LIMIT;
import static io.camunda.optimize.service.util.ExceptionUtil.isInstanceIndexNotFoundException;
import static java.util.stream.Collectors.toSet;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.AggregationBuilders;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsAggregate;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import io.camunda.optimize.dto.optimize.DefinitionType;
import io.camunda.optimize.service.db.es.OptimizeElasticsearchClient;
import io.camunda.optimize.service.db.es.builders.OptimizeSearchRequestBuilderES;
import io.camunda.optimize.service.db.reader.DefinitionInstanceReader;
import io.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import io.camunda.optimize.service.util.configuration.condition.ElasticSearchCondition;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

@AllArgsConstructor
@Component
@Slf4j
@Conditional(ElasticSearchCondition.class)
public class DefinitionInstanceReaderES extends DefinitionInstanceReader {

  private final OptimizeElasticsearchClient esClient;

  @Override
  public Set<String> getAllExistingDefinitionKeys(
      final DefinitionType type, final Set<String> instanceIds) {
    final String defKeyAggName = "definitionKeyAggregation";

    SearchRequest searchRequest =
        OptimizeSearchRequestBuilderES.of(
            b ->
                b.optimizeIndex(esClient, resolveIndexMultiAliasForType(type))
                    .source(s -> s.fetch(false))
                    .size(0)
                    .query(
                        q ->
                            q.bool(
                                bb -> {
                                  if (CollectionUtils.isEmpty(instanceIds)) {
                                    bb.must(m -> m.matchAll(a -> a));
                                  } else {
                                    bb.filter(
                                        f ->
                                            f.terms(
                                                t ->
                                                    t.field(resolveInstanceIdFieldForType(type))
                                                        .terms(
                                                            tt ->
                                                                tt.value(
                                                                    instanceIds.stream()
                                                                        .map(FieldValue::of)
                                                                        .toList()))));
                                  }
                                  return bb;
                                }))
                    .aggregations(
                        Map.of(
                            defKeyAggName,
                            AggregationBuilders.terms(
                                t ->
                                    t.field(resolveDefinitionKeyFieldForType(type))
                                        .size(MAX_RESPONSE_SIZE_LIMIT)))));

    final SearchResponse<?> response;
    try {
      response = esClient.search(searchRequest, Object.class);
    } catch (IOException e) {
      throw new OptimizeRuntimeException(
          String.format("Was not able to retrieve definition keys for instances of type %s", type),
          e);
    } catch (ElasticsearchException e) {
      if (isInstanceIndexNotFoundException(type, e)) {
        log.info(
            "Was not able to retrieve definition keys for instances because no {} instance indices exist. "
                + "Returning empty set.",
            type);
        return Collections.emptySet();
      }
      throw e;
    }

    StringTermsAggregate definitionKeyTerms = response.aggregations().get(defKeyAggName).sterms();
    return definitionKeyTerms.buckets().array().stream()
        .map(r -> r.key().stringValue())
        .collect(toSet());
  }
}
