/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.json.JsonpMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.webapps.schema.descriptors.IndexDescriptor;
import io.camunda.webapps.schema.descriptors.IndexTemplateDescriptor;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;

public final class SchemaTestUtil {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonpMapper ELS_JSON_MAPPER =
      new co.elastic.clients.json.jackson.JacksonJsonpMapper(MAPPER);
  private static final org.opensearch.client.json.JsonpMapper OPENSEARCH_JSON_MAPPER =
      new JacksonJsonpMapper(MAPPER);

  private SchemaTestUtil() {}

  public static IndexTemplateDescriptor mockIndexTemplate(
      final String indexName,
      final String indexPattern,
      final String alias,
      final List<String> composedOf,
      final String templateName,
      final String mappingsFileName) {
    final var descriptor = mock(IndexTemplateDescriptor.class);
    when(descriptor.getIndexName()).thenReturn(indexName);
    when(descriptor.getIndexPattern()).thenReturn(indexPattern);
    when(descriptor.getAlias()).thenReturn(alias);
    when(descriptor.getComposedOf()).thenReturn(composedOf);
    when(descriptor.getTemplateName()).thenReturn(templateName);
    when(descriptor.getMappingsClasspathFilename()).thenReturn(mappingsFileName);

    return descriptor;
  }

  public static IndexDescriptor mockIndex(
      final String fullQualifiedName,
      final String alias,
      final String indexName,
      final String mappingsFileName) {
    final var descriptor = mock(IndexDescriptor.class);
    when(descriptor.getFullQualifiedName()).thenReturn(fullQualifiedName);
    when(descriptor.getAlias()).thenReturn(alias);
    when(descriptor.getIndexName()).thenReturn(indexName);
    when(descriptor.getMappingsClasspathFilename()).thenReturn(mappingsFileName);
    when(descriptor.getAllVersionsIndexNameRegexPattern()).thenReturn(fullQualifiedName + ".*");

    return descriptor;
  }

  @SuppressWarnings("unchecked")
  public static void validateMappings(final TypeMapping mapping, final String fileName)
      throws IOException {
    final var propertiesMap = getFileProperties(fileName);

    assertThat(mapping.properties().size()).isEqualTo(propertiesMap.size());
    propertiesMap.forEach(
        (key, value) ->
            assertThat(mapping.properties().get(key)._kind().jsonValue())
                .isEqualTo(value.get("type")));
  }

  public static void validateMappings(
      final org.opensearch.client.opensearch._types.mapping.TypeMapping mapping,
      final String fileName)
      throws IOException {

    final var propertiesMap = getFileProperties(fileName);

    assertThat(mapping.properties().size()).isEqualTo(propertiesMap.size());
    propertiesMap.forEach(
        (key, value) ->
            assertThat(mapping.properties().get(key)._kind().jsonValue())
                .isEqualTo(value.get("type")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> getFileProperties(final String fileName)
      throws IOException {
    try (final var expectedMappings = SchemaTestUtil.class.getResourceAsStream(fileName)) {
      final var jsonMap =
          MAPPER.readValue(
              expectedMappings, new TypeReference<Map<String, Map<String, Object>>>() {});
      return (Map<String, Map<String, Object>>) jsonMap.get("mappings").get("properties");
    }
  }

  public static boolean mappingsMatch(final JsonNode mappings, final String fileName)
      throws IOException {
    try (final var expectedMappingsJson = SchemaTestUtil.class.getResourceAsStream(fileName)) {
      final var expectedMappingsTree = new ObjectMapper().readTree(expectedMappingsJson);
      return mappings.equals(expectedMappingsTree.get("mappings"));
    }
  }
}
