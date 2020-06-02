/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

package org.camunda.optimize.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.GatewayDirection;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowElementBuilder;
import org.camunda.bpm.model.bpmn.builder.AbstractFlowNodeBuilder;
import org.camunda.bpm.model.bpmn.builder.ProcessBuilder;
import org.camunda.bpm.model.bpmn.builder.StartEventBuilder;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.camunda.optimize.dto.optimize.query.event.AutogeneratedEventGraphDto;
import org.camunda.optimize.dto.optimize.query.event.AutogeneratedProcessModelDto;
import org.camunda.optimize.dto.optimize.query.event.EventMappingDto;
import org.camunda.optimize.dto.optimize.query.event.EventSequenceCountDto;
import org.camunda.optimize.dto.optimize.query.event.EventSourceEntryDto;
import org.camunda.optimize.dto.optimize.query.event.EventSourceType;
import org.camunda.optimize.dto.optimize.query.event.EventTypeDto;
import org.camunda.optimize.service.es.OptimizeElasticsearchClient;
import org.camunda.optimize.service.es.reader.EventSequenceCountReader;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.camunda.bpm.model.bpmn.GatewayDirection.Converging;
import static org.camunda.bpm.model.bpmn.GatewayDirection.Diverging;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.EXTERNAL_EVENTS_INDEX_SUFFIX;

@Component
@Slf4j
public class AutogeneratedProcessModelService {

  private static final String AUTOGENERATED_PROCESS_ID = "AutogeneratedProcessId";
  private static final String EVENT = "event";

  private final EventSequenceCountReader externalEventSequenceCounter;

  public AutogeneratedProcessModelService(final OptimizeElasticsearchClient esClient,
                                          final ObjectMapper objectMapper,
                                          final ConfigurationService configurationService) {
    this.externalEventSequenceCounter = new EventSequenceCountReader(
      EXTERNAL_EVENTS_INDEX_SUFFIX,
      esClient,
      objectMapper,
      configurationService
    );
  }

  public AutogeneratedProcessModelDto generateModelFromEventSources(List<EventSourceEntryDto> eventSources) {
    // This will eventually be removed when support exists for Camunda sources and even mixed sources
    if (eventSources.stream().anyMatch(source -> source.getType().equals(EventSourceType.CAMUNDA))) {
      log.warn("Autogeneration is only supported for an external event source");
      return createEmptyProcessModel();
    }

    final AutogeneratedEventGraphDto autogeneratedEventGraphDto = generateExternalEventGraph();
    if (autogeneratedEventGraphDto.getStartEvents().isEmpty()) {
      log.warn("Cannot generate a model as no eligible start events found");
      return createEmptyProcessModel();
    }
    if (autogeneratedEventGraphDto.getEndEvents().isEmpty()) {
      log.warn("Cannot generate a model as no eligible end events found");
      return createEmptyProcessModel();
    }

    Map<String, EventMappingDto> mappings = new HashMap<>();
    ProcessBuilder diagramBuilder = Bpmn.createExecutableProcess(AUTOGENERATED_PROCESS_ID);
    autogeneratedEventGraphDto.getStartEvents().forEach(rootNode -> {
      final String nodeId = generateNodeId(rootNode);
      log.debug("Adding start event with id {} to autogenerated model", nodeId);
      StartEventBuilder startEventBuilder = diagramBuilder.startEvent(nodeId)
        .message(rootNode.getEventName())
        .name(rootNode.getEventName());
      mappings.put(nodeId, EventMappingDto.builder().start(rootNode).build());
      depthTraverseGraph(
        rootNode,
        autogeneratedEventGraphDto,
        startEventBuilder,
        mappings
      );
    });
    final BpmnModelInstance modelInstance = diagramBuilder.done();

    return AutogeneratedProcessModelDto.builder()
      .xml(Bpmn.convertToString(modelInstance))
      .mappings(mappings)
      .build();
  }

  private void depthTraverseGraph(EventTypeDto previouslyAddedNode,
                                  AutogeneratedEventGraphDto graphDto,
                                  AbstractFlowNodeBuilder<?, ?> currentNodeBuilder,
                                  Map<String, EventMappingDto> mappings) {
    final List<EventTypeDto> nodesToAdd = graphDto.getSourceToTargetEventsMap().get(previouslyAddedNode);
    if (nodesToAdd.size() > 1) {
      currentNodeBuilder = addGateway(currentNodeBuilder, previouslyAddedNode, Diverging);
    }
    for (EventTypeDto nodeToAdd : nodesToAdd) {
      BpmnModelInstance currentFlowNodeInstance = currentNodeBuilder.done();
      String nodeId = generateNodeId(nodeToAdd);
      ModelElementInstance existingModelElement = currentFlowNodeInstance.getModelElementById(nodeId);
      if (graphDto.getEndEvents().contains(nodeToAdd)) {
        if (!nodeAlreadyAddedToModel(existingModelElement)) {
          if (nodeSucceedsGateway(nodeToAdd, graphDto)) {
            currentNodeBuilder = addGateway(currentNodeBuilder, nodeToAdd, Converging);
          }
          addEndEvent(currentNodeBuilder, nodeToAdd, nodeId);
          mappings.put(nodeId, EventMappingDto.builder().start(nodeToAdd).build());
        } else {
          if (nodeSucceedsGateway(nodeToAdd, graphDto)) {
            final String existingGatewayId = generateGatewayIdForNode(nodeToAdd, Converging);
            log.debug("Connecting to gateway with id {}", existingGatewayId);
            currentNodeBuilder.connectTo(existingGatewayId);
          } else {
            log.debug("Connecting to end event with id {}", nodeId);
            currentNodeBuilder.connectTo(nodeId);
          }
        }
      } else {
        if (!nodeAlreadyAddedToModel(existingModelElement)) {
          if (nodeSucceedsGateway(nodeToAdd, graphDto)) {
            currentNodeBuilder = addGateway(currentNodeBuilder, nodeToAdd, Converging);
          }
          AbstractFlowNodeBuilder<?, ?> nextBuilder = addIntermediateEvent(currentNodeBuilder, nodeToAdd, nodeId);
          mappings.put(nodeId, EventMappingDto.builder().start(nodeToAdd).build());
          depthTraverseGraph(nodeToAdd, graphDto, nextBuilder, mappings);
        } else {
          // If it has already been added, we know that it must follow a gateway as more than one event precede it
          final String existingGatewayId = generateGatewayIdForNode(nodeToAdd, Converging);
          log.debug("Connecting to gateway with id {}", existingGatewayId);
          currentNodeBuilder.connectTo(existingGatewayId);
        }
      }
    }
  }

  private AbstractFlowNodeBuilder<?, ?> addIntermediateEvent(final AbstractFlowNodeBuilder<?, ?> currentNodeBuilder,
                                                             final EventTypeDto nodeToAdd, final String nodeId) {
    log.debug("Adding intermediate event with id {} to autogenerated model", nodeId);
    return currentNodeBuilder.intermediateCatchEvent(nodeId)
      .message(nodeToAdd.getEventName())
      .name(nodeToAdd.getEventName());
  }

  private AbstractFlowElementBuilder<?, ?> addEndEvent(final AbstractFlowNodeBuilder<?, ?> currentNodeBuilder,
                                                       final EventTypeDto nodeToAdd, final String nodeId) {
    log.debug("Adding end event with id {} to autogenerated model", nodeId);
    return currentNodeBuilder.endEvent(nodeId)
      .message(nodeToAdd.getEventName())
      .name(nodeToAdd.getEventName());
  }

  private AbstractFlowNodeBuilder<?, ?> addGateway(AbstractFlowNodeBuilder<?, ?> currentNodeBuilder,
                                                   final EventTypeDto nodeToAdd,
                                                   final GatewayDirection gatewayDirection) {
    final String gatewayId = generateGatewayIdForNode(nodeToAdd, gatewayDirection);
    log.debug("Adding {} exclusive gateway with id {} to autogenerated model", gatewayDirection.toString(), gatewayId);
    currentNodeBuilder = currentNodeBuilder.exclusiveGateway(gatewayId)
      .name(generateGatewayName(nodeToAdd, gatewayDirection));
    return currentNodeBuilder;
  }

  private boolean nodeAlreadyAddedToModel(final ModelElementInstance existingModelElement) {
    return existingModelElement != null;
  }

  private boolean nodeSucceedsGateway(final EventTypeDto eventTypeDto, final AutogeneratedEventGraphDto graphDto) {
    final List<Map.Entry<EventTypeDto, List<EventTypeDto>>> precedingNodes = graphDto.getSourceToTargetEventsMap()
      .entrySet()
      .stream()
      .filter(entry -> entry.getValue().contains(eventTypeDto))
      .collect(Collectors.toList());
    return precedingNodes.size() > 1;
  }

  private AutogeneratedEventGraphDto generateExternalEventGraph() {
    Map<EventTypeDto, List<EventTypeDto>> sourceToTargetEventsMap = new HashMap<>();
    final List<EventSequenceCountDto> externalEventSequenceCounts = externalEventSequenceCounter.getAllSequenceCounts();
    externalEventSequenceCounts
      .forEach(eventSequenceCountDto -> {
        if (eventSequenceCountDto.getTargetEvent() == null) {
          if (!sourceToTargetEventsMap.containsKey(eventSequenceCountDto.getSourceEvent())) {
            sourceToTargetEventsMap.put(eventSequenceCountDto.getSourceEvent(), new ArrayList<>());
          }
        } else {
          if (sourceToTargetEventsMap.containsKey(eventSequenceCountDto.getSourceEvent())) {
            sourceToTargetEventsMap.get(eventSequenceCountDto.getSourceEvent())
              .add(eventSequenceCountDto.getTargetEvent());
          } else {
            List<EventTypeDto> targetEvents = new ArrayList<>();
            targetEvents.add(eventSequenceCountDto.getTargetEvent());
            sourceToTargetEventsMap.put(eventSequenceCountDto.getSourceEvent(), targetEvents);
          }
        }
      });

    final List<EventTypeDto> targetEvents = sourceToTargetEventsMap.values()
      .stream()
      .flatMap(Collection::stream)
      .distinct()
      .collect(Collectors.toList());
    List<EventTypeDto> startEvents = new ArrayList<>(sourceToTargetEventsMap.keySet());
    startEvents.removeAll(targetEvents);
    List<EventTypeDto> endEvents = sourceToTargetEventsMap.keySet().stream()
      .filter(endNodeCandidate -> sourceToTargetEventsMap.get(endNodeCandidate).isEmpty())
      .collect(Collectors.toList());

    return AutogeneratedEventGraphDto.builder()
      .startEvents(startEvents)
      .endEvents(endEvents)
      .sourceToTargetEventsMap(sourceToTargetEventsMap)
      .build();
  }

  private AutogeneratedProcessModelDto createEmptyProcessModel() {
    return AutogeneratedProcessModelDto.builder()
      .xml(Bpmn.convertToString(Bpmn.createExecutableProcess(AUTOGENERATED_PROCESS_ID).done()))
      .mappings(Collections.emptyMap())
      .build();
  }

  private String generateGatewayName(final EventTypeDto node, final GatewayDirection direction) {
    return String.join("_", Arrays.asList(direction.toString(), node.getEventName()));
  }

  public static String generateGatewayIdForNode(final EventTypeDto eventTypeDto, GatewayDirection gatewayDirection) {
    return generateId(gatewayDirection.toString().toLowerCase(), eventTypeDto);
  }

  public static String generateNodeId(final EventTypeDto eventTypeDto) {
    return generateId(EVENT, eventTypeDto);
  }

  private static String generateId(String type, EventTypeDto eventTypeDto) {
    // The type prefix is necessary and should start with lower case so that the ID passes QName validation
    return String.join(
      "_",
      Arrays.asList(
        type,
        eventTypeDto.getGroup(),
        eventTypeDto.getSource(),
        eventTypeDto.getEventName()
      )
    );
  }

}
