/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.schema;

import org.camunda.optimize.service.db.schema.index.DecisionInstanceIndex;
import org.camunda.optimize.service.db.schema.index.ProcessInstanceIndex;
import org.camunda.optimize.service.db.schema.index.RichClient;
import org.camunda.optimize.service.db.schema.index.events.CamundaActivityEventIndex;
import org.camunda.optimize.service.db.schema.index.events.EventSequenceCountIndex;
import org.camunda.optimize.service.db.schema.index.events.EventTraceStateIndex;
import org.camunda.optimize.service.es.OptimizeElasticsearchClient;
import org.camunda.optimize.service.es.schema.IndexMappingCreator;
import org.camunda.optimize.service.es.schema.index.DecisionInstanceIndexES;
import org.camunda.optimize.service.es.schema.index.ProcessInstanceIndexES;
import org.camunda.optimize.service.es.schema.index.events.CamundaActivityEventIndexES;
import org.camunda.optimize.service.es.schema.index.events.EventSequenceCountIndexES;
import org.camunda.optimize.service.es.schema.index.events.EventTraceStateIndexES;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.camunda.optimize.service.db.DatabaseConstants.CAMUNDA_ACTIVITY_EVENT_INDEX_PREFIX;
import static org.camunda.optimize.service.db.DatabaseConstants.DECISION_INSTANCE_INDEX_PREFIX;
import static org.camunda.optimize.service.db.DatabaseConstants.EVENT_SEQUENCE_COUNT_INDEX_PREFIX;
import static org.camunda.optimize.service.db.DatabaseConstants.EVENT_TRACE_STATE_INDEX_PREFIX;

public abstract class MappingMetadataUtil {

  protected final RichClient dbClient;

  protected MappingMetadataUtil(RichClient dbClient) {
    this.dbClient = dbClient;
  }

  public List<IndexMappingCreator<?>> getAllMappings() {
    List<IndexMappingCreator<?>> allMappings = new ArrayList<>();
    allMappings.addAll(getAllNonDynamicMappings());
    allMappings.addAll(getAllDynamicMappings());
    return allMappings;
  }

  protected abstract Collection<? extends IndexMappingCreator<?>> getAllNonDynamicMappings();

  public List<IndexMappingCreator<?>> getAllDynamicMappings() {
    List<IndexMappingCreator<?>> dynamicMappings = new ArrayList<>();
    dynamicMappings.addAll(retrieveAllCamundaActivityEventIndices());
    dynamicMappings.addAll(retrieveAllSequenceCountIndices());
    dynamicMappings.addAll(retrieveAllEventTraceIndices());
    dynamicMappings.addAll(retrieveAllProcessInstanceIndices());
    dynamicMappings.addAll(retrieveAllDecisionInstanceIndices());
    return dynamicMappings;
  }

  public abstract List<String> retrieveProcessInstanceIndexIdentifiers(final boolean eventBased);

  private List<? extends DecisionInstanceIndex<?>> retrieveAllDecisionInstanceIndices() {
    return retrieveAllDynamicIndexKeysForPrefix(DECISION_INSTANCE_INDEX_PREFIX)
      .stream()
      .map(key -> dbClient instanceof OptimizeElasticsearchClient ?
        new DecisionInstanceIndexES(key) :
        null)// TODO Not implemented for OpenSearch yet, to be done with OPT-7349
      .toList();
  }

  private List<? extends CamundaActivityEventIndex<?>> retrieveAllCamundaActivityEventIndices() {
    return retrieveAllDynamicIndexKeysForPrefix(CAMUNDA_ACTIVITY_EVENT_INDEX_PREFIX)
      .stream()
      .map(key -> dbClient instanceof OptimizeElasticsearchClient ?
        new CamundaActivityEventIndexES(key) :
        null)// TODO Not implemented for OpenSearch yet, to be done with OPT-7349
      .toList();
  }

  private List<? extends EventSequenceCountIndex<?>> retrieveAllSequenceCountIndices() {
    return retrieveAllDynamicIndexKeysForPrefix(EVENT_SEQUENCE_COUNT_INDEX_PREFIX)
      .stream()
      .map(key -> dbClient instanceof OptimizeElasticsearchClient ?
        new EventSequenceCountIndexES(key) :
        null)// TODO Not implemented for OpenSearch yet, to be done with OPT-7349
      .toList();
  }

  private List<? extends EventTraceStateIndex<?>> retrieveAllEventTraceIndices() {
    return retrieveAllDynamicIndexKeysForPrefix(EVENT_TRACE_STATE_INDEX_PREFIX)
      .stream()
      .map(key -> dbClient instanceof OptimizeElasticsearchClient ?
        new EventTraceStateIndexES(key) :
        null)// TODO Not implemented for OpenSearch yet, to be done with OPT-7349
      .toList();
  }

  private List<? extends ProcessInstanceIndex<?>> retrieveAllProcessInstanceIndices() {
    return retrieveProcessInstanceIndexIdentifiers(false)
      .stream()
      .map(key -> dbClient instanceof OptimizeElasticsearchClient ?
        new ProcessInstanceIndexES(key) :
        null)// TODO Not implemented for OpenSearch yet, to be done with OPT-7349
      .toList();
  }

  protected abstract List<String> retrieveAllDynamicIndexKeysForPrefix(final String dynamicIndexPrefix);

}
