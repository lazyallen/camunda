/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.schema.elasticsearch;

import static io.camunda.exporter.utils.SearchEngineClientUtils.allImportersCompleted;
import static io.camunda.exporter.utils.SearchEngineClientUtils.appendToFileSchemaSettings;
import static io.camunda.exporter.utils.SearchEngineClientUtils.listIndices;
import static io.camunda.exporter.utils.SearchEngineClientUtils.mapToSettings;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.ilm.PutLifecycleRequest;
import co.elastic.clients.elasticsearch.indices.Alias;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutIndicesSettingsRequest;
import co.elastic.clients.elasticsearch.indices.PutMappingRequest;
import co.elastic.clients.elasticsearch.indices.get_index_template.IndexTemplateItem;
import co.elastic.clients.elasticsearch.indices.put_index_template.IndexTemplateMapping;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.json.JsonpDeserializer;
import co.elastic.clients.json.jackson.JacksonJsonpGenerator;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.exporter.SchemaResourceSerializer;
import io.camunda.exporter.config.ExporterConfiguration.IndexSettings;
import io.camunda.exporter.exceptions.ElasticsearchExporterException;
import io.camunda.exporter.exceptions.IndexSchemaValidationException;
import io.camunda.exporter.schema.IndexMapping;
import io.camunda.exporter.schema.IndexMappingProperty;
import io.camunda.exporter.schema.MappingSource;
import io.camunda.exporter.schema.SearchEngineClient;
import io.camunda.webapps.schema.descriptors.ImportValueTypes;
import io.camunda.webapps.schema.descriptors.IndexDescriptor;
import io.camunda.webapps.schema.descriptors.IndexTemplateDescriptor;
import io.camunda.webapps.schema.descriptors.operate.index.ImportPositionIndex;
import io.camunda.webapps.schema.entities.operate.ImportPositionEntity;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElasticsearchEngineClient implements SearchEngineClient {
  private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchEngineClient.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private final ElasticsearchClient client;

  public ElasticsearchEngineClient(final ElasticsearchClient client) {
    this.client = client;
  }

  @Override
  public void createIndex(final IndexDescriptor indexDescriptor, final IndexSettings settings) {
    final CreateIndexRequest request = createIndexRequest(indexDescriptor, settings);
    try {
      client.indices().create(request);
      LOG.debug("Index [{}] was successfully created", indexDescriptor.getIndexName());
    } catch (final IOException | ElasticsearchException e) {
      final var errMsg =
          String.format("Index [%s] was not created", indexDescriptor.getIndexName());
      LOG.error(errMsg, e);
      throw new ElasticsearchExporterException(errMsg, e);
    }
  }

  @Override
  public void createIndexTemplate(
      final IndexTemplateDescriptor templateDescriptor,
      final IndexSettings settings,
      final boolean create) {
    final PutIndexTemplateRequest request =
        putIndexTemplateRequest(templateDescriptor, settings, create);

    try {
      client.indices().putIndexTemplate(request);
      LOG.debug("Template [{}] was successfully created", templateDescriptor.getTemplateName());
    } catch (final IOException e) {
      final var errMsg =
          String.format("Template [%s] was NOT created", templateDescriptor.getTemplateName());
      LOG.error(errMsg, e);
      throw new ElasticsearchExporterException(errMsg, e);
    } catch (final ElasticsearchException e) {
      // Creation should only occur once during initialisation but multiple partitions with
      // their own exporter will create race conditions where multiple exporters try to
      // create the same template
      final var errorReason = e.error().reason();
      if (errorReason != null
          && errorReason.equals(
              "index template [" + templateDescriptor.getTemplateName() + "] already exists")) {
        LOG.debug(errorReason);
        return;
      }

      LOG.error(errorReason, e);
      throw new ElasticsearchExporterException(errorReason, e);
    }
  }

  @Override
  public void putMapping(
      final IndexDescriptor indexDescriptor, final Collection<IndexMappingProperty> newProperties) {
    final PutMappingRequest request = putMappingRequest(indexDescriptor, newProperties);

    try {
      client.indices().putMapping(request);
      LOG.debug("Mapping in [{}] was successfully updated", indexDescriptor.getIndexName());
    } catch (final IOException | ElasticsearchException e) {
      final var errMsg =
          String.format("Mapping in [%s] was NOT updated", indexDescriptor.getIndexName());
      LOG.error(errMsg, e);
      throw new ElasticsearchExporterException(errMsg, e);
    }
  }

  @Override
  public Map<String, IndexMapping> getMappings(
      final String namePattern, final MappingSource mappingSource) {
    try {
      final Map<String, TypeMapping> mappings = getCurrentMappings(mappingSource, namePattern);

      return mappings.entrySet().stream()
          .collect(
              Collectors.toMap(
                  Entry::getKey,
                  entry -> {
                    final var mappingsBlock = entry.getValue();
                    return new IndexMapping.Builder()
                        .indexName(entry.getKey())
                        .dynamic(dynamicFromMappings(mappingsBlock))
                        .properties(propertiesFromMappings(mappingsBlock))
                        .metaProperties(metaFromMappings(mappingsBlock))
                        .build();
                  }));
    } catch (final IOException | ElasticsearchException e) {
      throw new ElasticsearchExporterException(
          String.format(
              "Failed retrieving mappings from index/index templates with pattern [%s]",
              namePattern),
          e);
    }
  }

  @Override
  public void putSettings(
      final List<IndexDescriptor> indexDescriptors, final Map<String, String> toAppendSettings) {
    final var request = putIndexSettingsRequest(indexDescriptors, toAppendSettings);

    try {
      client.indices().putSettings(request);
    } catch (final IOException | ElasticsearchException e) {
      final var errMsg =
          String.format(
              "settings PUT failed for the following indices [%s]", listIndices(indexDescriptors));
      LOG.error(errMsg, e);
      throw new ElasticsearchExporterException(errMsg, e);
    }
  }

  @Override
  public void putIndexLifeCyclePolicy(final String policyName, final String deletionMinAge) {
    final PutLifecycleRequest request = putLifecycleRequest(policyName, deletionMinAge);

    try {
      client.ilm().putLifecycle(request);
    } catch (final IOException e) {
      final var errMsg = String.format("Index lifecycle policy [%s] failed to PUT", policyName);
      LOG.error(errMsg, e);
      throw new ElasticsearchExporterException(errMsg, e);
    }
  }

  @Override
  public boolean importersCompleted(final int partitionId, final String indexPrefix) {
    final var importPositionSearchRequest = importPositionDocuments(partitionId, indexPrefix);

    try {
      final var docs = client.search(importPositionSearchRequest, ImportPositionEntity.class);
      return allImportersCompleted(
          docs.hits().hits().stream().map(Hit::source).toList(), ImportValueTypes.values().length);
    } catch (final IOException e) {
      final var errMsg =
          String.format(
              "Failed to search documents in the import position index for partition [%s]",
              partitionId);
      LOG.error(errMsg, e);
      throw new ElasticsearchExporterException(errMsg, e);
    }
  }

  private SearchRequest importPositionDocuments(final int partitionId, final String indexPrefix) {
    final var importPositionIndexName =
        new ImportPositionIndex(indexPrefix, true).getFullQualifiedName();
    return new SearchRequest.Builder()
        .index(importPositionIndexName)
        .size(ImportValueTypes.values().length)
        .query(q -> q.match(m -> m.field(ImportPositionIndex.PARTITION_ID).query(partitionId)))
        .build();
  }

  private PutIndicesSettingsRequest putIndexSettingsRequest(
      final List<IndexDescriptor> indexDescriptors, final Map<String, String> toAppendSettings) {
    final co.elastic.clients.elasticsearch.indices.IndexSettings settings =
        mapToSettings(
            toAppendSettings,
            (inp) ->
                deserializeJson(
                    co.elastic.clients.elasticsearch.indices.IndexSettings._DESERIALIZER, inp));
    return new PutIndicesSettingsRequest.Builder()
        .index(listIndices(indexDescriptors))
        .settings(settings)
        .build();
  }

  public PutLifecycleRequest putLifecycleRequest(
      final String policyName, final String deletionMinAge) {
    return new PutLifecycleRequest.Builder()
        .name(policyName)
        .policy(
            policy ->
                policy.phases(
                    phase ->
                        phase.delete(
                            del ->
                                del.minAge(m -> m.time(deletionMinAge))
                                    .actions(JsonData.of(Map.of("delete", Map.of()))))))
        .build();
  }

  private Map<String, TypeMapping> getCurrentMappings(
      final MappingSource mappingSource, final String namePattern) throws IOException {
    if (mappingSource == MappingSource.INDEX) {
      return client
          .indices()
          .getMapping(req -> req.index(namePattern).ignoreUnavailable(true))
          .result()
          .entrySet()
          .stream()
          .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().mappings()));
    } else if (mappingSource == MappingSource.INDEX_TEMPLATE) {
      return client
          .indices()
          .getIndexTemplate(req -> req.name(namePattern))
          .indexTemplates()
          .stream()
          .filter(
              indexTemplateItem ->
                  indexTemplateItem.indexTemplate().template() != null
                      && indexTemplateItem.indexTemplate().template().mappings() != null)
          .collect(
              Collectors.toMap(
                  IndexTemplateItem::name, item -> item.indexTemplate().template().mappings()));
    } else {
      throw new IndexSchemaValidationException(
          "Invalid mapping source provided must be either INDEX or INDEX_TEMPLATE");
    }
  }

  private Set<IndexMappingProperty> propertiesFromMappings(final TypeMapping mapping) {
    return mapping.properties().entrySet().stream()
        .map(
            p ->
                new IndexMappingProperty.Builder()
                    .name(p.getKey())
                    .typeDefinition(propertyToMap(p.getValue()))
                    .build())
        .collect(Collectors.toSet());
  }

  private Map<String, Object> propertyToMap(final Property property) {
    try {
      return SchemaResourceSerializer.serialize(
          (JacksonJsonpGenerator::new),
          (jacksonJsonpGenerator) ->
              property.serialize(jacksonJsonpGenerator, new JacksonJsonpMapper(MAPPER)));
    } catch (final IOException e) {
      throw new ElasticsearchExporterException(
          String.format("Failed to serialize property [%s]", property.toString()), e);
    }
  }

  private String dynamicFromMappings(final TypeMapping mapping) {
    final var dynamic = mapping.dynamic();
    return dynamic == null ? "strict" : dynamic.toString().toLowerCase();
  }

  private Map<String, Object> metaFromMappings(final TypeMapping mapping) {
    return mapping.meta().entrySet().stream()
        .collect(Collectors.toMap(Entry::getKey, ent -> ent.getValue().to(Object.class)));
  }

  private PutMappingRequest putMappingRequest(
      final IndexDescriptor indexDescriptor, final Collection<IndexMappingProperty> newProperties) {

    final var elsProperties =
        newProperties.stream()
            .map(IndexMappingProperty::toElasticsearchProperty)
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

    return new PutMappingRequest.Builder()
        .index(indexDescriptor.getFullQualifiedName())
        .properties(elsProperties)
        .build();
  }

  public <T> T deserializeJson(final JsonpDeserializer<T> deserializer, final InputStream json) {
    try (final var parser = client._jsonpMapper().jsonProvider().createParser(json)) {
      return deserializer.deserialize(parser, client._jsonpMapper());
    }
  }

  private InputStream getResourceAsStream(final String classpathFileName) {
    return getClass().getResourceAsStream(classpathFileName);
  }

  private PutIndexTemplateRequest putIndexTemplateRequest(
      final IndexTemplateDescriptor indexTemplateDescriptor,
      final IndexSettings settings,
      final Boolean create) {

    try (final var templateFile =
        getResourceAsStream(indexTemplateDescriptor.getMappingsClasspathFilename())) {

      final var templateFields =
          deserializeJson(
              IndexTemplateMapping._DESERIALIZER,
              appendToFileSchemaSettings(templateFile, settings));

      return new PutIndexTemplateRequest.Builder()
          .name(indexTemplateDescriptor.getTemplateName())
          .indexPatterns(indexTemplateDescriptor.getIndexPattern())
          .template(
              t ->
                  t.aliases(indexTemplateDescriptor.getAlias(), Alias.of(a -> a))
                      .mappings(templateFields.mappings())
                      .settings(templateFields.settings()))
          .composedOf(indexTemplateDescriptor.getComposedOf())
          .create(create)
          .build();
    } catch (final IOException e) {
      throw new ElasticsearchExporterException(
          "Failed to load file "
              + indexTemplateDescriptor.getMappingsClasspathFilename()
              + " from classpath.",
          e);
    }
  }

  private CreateIndexRequest createIndexRequest(
      final IndexDescriptor indexDescriptor, final IndexSettings settings) {
    try (final var templateFile =
        getResourceAsStream(indexDescriptor.getMappingsClasspathFilename())) {

      final var templateFields =
          deserializeJson(
              IndexTemplateMapping._DESERIALIZER,
              appendToFileSchemaSettings(templateFile, settings));

      return new CreateIndexRequest.Builder()
          .index(indexDescriptor.getFullQualifiedName())
          .aliases(indexDescriptor.getAlias(), a -> a.isWriteIndex(false))
          .mappings(templateFields.mappings())
          .settings(templateFields.settings())
          .build();
    } catch (final IOException e) {
      throw new ElasticsearchExporterException(
          "Failed to load file "
              + indexDescriptor.getMappingsClasspathFilename()
              + " from classpath.",
          e);
    }
  }
}
