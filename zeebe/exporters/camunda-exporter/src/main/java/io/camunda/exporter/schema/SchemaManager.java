/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.exporter.config.ExporterConfiguration;
import io.camunda.exporter.config.ExporterConfiguration.IndexSettings;
import io.camunda.exporter.config.ExporterConfiguration.RetentionConfiguration;
import io.camunda.webapps.schema.descriptors.IndexDescriptor;
import io.camunda.webapps.schema.descriptors.IndexTemplateDescriptor;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaManager {
  private static final Logger LOG = LoggerFactory.getLogger(SchemaManager.class);
  private final SearchEngineClient searchEngineClient;
  private final Collection<IndexDescriptor> indexDescriptors;
  private final Collection<IndexTemplateDescriptor> indexTemplateDescriptors;
  private final ExporterConfiguration config;
  private final ObjectMapper objectMapper;

  public SchemaManager(
      final SearchEngineClient searchEngineClient,
      final Collection<IndexDescriptor> indexDescriptors,
      final Collection<IndexTemplateDescriptor> indexTemplateDescriptors,
      final ExporterConfiguration config,
      final ObjectMapper objectMapper) {
    this.searchEngineClient = searchEngineClient;
    this.indexDescriptors = indexDescriptors;
    this.indexTemplateDescriptors = indexTemplateDescriptors;
    this.config = config;
    this.objectMapper = objectMapper;
  }

  public void startup() {
    if (!config.isCreateSchema()) {
      LOG.info(
          "Will not make any changes to indices and index templates as [createSchema] is false");
      return;
    }
    LOG.info("Schema creation is enabled. Start Schema management.");
    final var schemaValidator = new IndexSchemaValidator(objectMapper);
    final var newIndexProperties = validateIndices(schemaValidator);
    final var newIndexTemplateProperties = validateIndexTemplates(schemaValidator);
    //  used to create any indices/templates which don't exist
    initialiseResources();

    //  used to update existing indices/templates
    LOG.info("Update index schema. '{}' indices need to be updated", newIndexProperties.size());
    updateSchema(newIndexProperties);
    LOG.info(
        "Update index template schema. '{}' index templates need to be updated",
        newIndexProperties.size());
    updateSchema(newIndexTemplateProperties);

    final RetentionConfiguration retention = config.getRetention();
    if (retention.isEnabled()) {
      LOG.info(
          "Retention is enabled. Create ILM policy [name: '{}', retention: '{}']",
          retention.getPolicyName(),
          retention.getMinimumAge());
      searchEngineClient.putIndexLifeCyclePolicy(
          retention.getPolicyName(), retention.getMinimumAge());
    }
    LOG.info("Schema management completed.");
  }

  public void initialiseResources() {
    initialiseIndices();
    initialiseIndexTemplates();
  }

  private void initialiseIndices() {
    if (indexDescriptors.isEmpty()) {
      LOG.info("Do not create any indices, as descriptors are missing");
      return;
    }

    final var existingIndexNames =
        searchEngineClient.getMappings(allIndexNames(), MappingSource.INDEX).keySet();

    LOG.info(
        "Found '{}' existing indices. Create missing index templates based on '{}' descriptors.",
        existingIndexNames.size(),
        indexTemplateDescriptors.size());
    indexDescriptors.stream()
        .filter(descriptor -> !existingIndexNames.contains(descriptor.getFullQualifiedName()))
        .forEach(
            descriptor -> {
              LOG.info("Create missing index '{}'", descriptor.getFullQualifiedName());
              searchEngineClient.createIndex(
                  descriptor, getIndexSettings(descriptor.getIndexName()));
            });
  }

  private void initialiseIndexTemplates() {
    if (indexTemplateDescriptors.isEmpty()) {
      LOG.info("Do not create any index templates, as descriptors are missing");
      return;
    }

    final var existingTemplateNames =
        searchEngineClient
            .getMappings(config.getIndex().getPrefix() + "*", MappingSource.INDEX_TEMPLATE)
            .keySet();

    LOG.info(
        "Found '{}' existing index templates. Create missing index templates based on '{}' descriptors.",
        existingTemplateNames.size(),
        indexTemplateDescriptors.size());
    indexTemplateDescriptors.stream()
        .filter(descriptor -> !existingTemplateNames.contains(descriptor.getTemplateName()))
        .forEach(
            descriptor -> {
              LOG.info("Create missing index template '{}'", descriptor.getTemplateName());
              searchEngineClient.createIndexTemplate(
                  descriptor, getIndexSettings(descriptor.getIndexName()), true);
              LOG.info(
                  "Create missing index '{}', for template '{}'",
                  descriptor.getFullQualifiedName(),
                  descriptor.getTemplateName());
              searchEngineClient.createIndex(
                  descriptor, getIndexSettings(descriptor.getIndexName()));
            });
  }

  public void updateSchema(final Map<IndexDescriptor, Collection<IndexMappingProperty>> newFields) {
    for (final var newFieldEntry : newFields.entrySet()) {
      final var descriptor = newFieldEntry.getKey();
      final var newProperties = newFieldEntry.getValue();

      if (descriptor instanceof IndexTemplateDescriptor) {
        LOG.info(
            "Updating template: '{}'", ((IndexTemplateDescriptor) descriptor).getTemplateName());
        searchEngineClient.createIndexTemplate(
            (IndexTemplateDescriptor) descriptor,
            getIndexSettings(descriptor.getIndexName()),
            false);
      } else {
        LOG.info(
            "Index alias: '{}'. New fields will be added '{}'",
            descriptor.getFullQualifiedName(),
            newProperties);
      }
      searchEngineClient.putMapping(descriptor, newProperties);
    }
  }

  private IndexSettings getIndexSettings(final String indexName) {
    final var templateReplicas =
        config
            .getIndex()
            .getReplicasByIndexName()
            .getOrDefault(indexName, config.getIndex().getNumberOfReplicas());
    final var templateShards =
        config
            .getIndex()
            .getShardsByIndexName()
            .getOrDefault(indexName, config.getIndex().getNumberOfShards());

    final var settings = new IndexSettings();
    settings.setNumberOfShards(templateShards);
    settings.setNumberOfReplicas(templateReplicas);

    return settings;
  }

  private Map<IndexDescriptor, Collection<IndexMappingProperty>> validateIndices(
      final IndexSchemaValidator schemaValidator) {
    if (indexDescriptors.isEmpty()) {
      LOG.info("No validation of indices, as there are no descriptors");
      return Map.of();
    }

    final var currentIndices = searchEngineClient.getMappings(allIndexNames(), MappingSource.INDEX);
    LOG.info(
        "Validate '{}' existing indices based on '{}' descriptors",
        currentIndices.size(),
        indexDescriptors.size());
    return schemaValidator.validateIndexMappings(currentIndices, indexDescriptors);
  }

  private Map<IndexDescriptor, Collection<IndexMappingProperty>> validateIndexTemplates(
      final IndexSchemaValidator schemaValidator) {
    if (indexTemplateDescriptors.isEmpty()) {
      LOG.info("No validation of index templates, as there are no descriptors");
      return Map.of();
    }

    final var currentTemplates =
        searchEngineClient.getMappings(
            config.getIndex().getPrefix() + "*", MappingSource.INDEX_TEMPLATE);

    LOG.info(
        "Validate '{}' existing index templates based on '{}' template descriptors",
        currentTemplates.size(),
        indexTemplateDescriptors.size());
    return schemaValidator.validateIndexMappings(
        currentTemplates,
        indexTemplateDescriptors.stream()
            .map(IndexDescriptor.class::cast)
            .collect(Collectors.toSet()));
  }

  private String allIndexNames() {

    // The wildcard is required as without it, requests would fail if the index didn't exist.
    // this way all descriptors can be retrieved in one request without errors due to not created
    // indices

    return indexDescriptors.stream()
        .map(descriptor -> descriptor.getFullQualifiedName() + "*")
        .collect(Collectors.joining(","));
  }
}
