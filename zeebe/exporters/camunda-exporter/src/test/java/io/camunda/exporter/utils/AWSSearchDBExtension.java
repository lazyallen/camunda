/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.exporter.utils;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.camunda.exporter.config.ExporterConfiguration;
import io.camunda.exporter.config.ExporterConfiguration.IndexSettings;
import io.camunda.exporter.schema.opensearch.OpensearchEngineClient;
import io.camunda.search.connect.os.OpensearchConnector;
import java.util.UUID;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opensearch.client.opensearch.OpenSearchClient;

public class AWSSearchDBExtension extends SearchDBExtension {

  private static OpenSearchClient osClient;

  private final String osUrl;

  public AWSSearchDBExtension(final String openSearchAwsInstanceUrl) {
    osUrl = openSearchAwsInstanceUrl;
  }

  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    final var osConfig = new ExporterConfiguration();
    osConfig.getConnect().setType("opensearch");
    osConfig.getConnect().setUrl(osUrl);
    osConfig.getIndex().setPrefix("test-" + UUID.randomUUID());
    osClient = new OpensearchConnector(osConfig.getConnect()).createClient();
  }

  @Override
  public void afterEach(final ExtensionContext context) throws Exception {
    osClient.indices().delete(req -> req.index(PROCESS_INDEX.getFullQualifiedName()));
  }

  @Override
  public void beforeEach(final ExtensionContext context) throws Exception {
    new OpensearchEngineClient(osClient).createIndex(PROCESS_INDEX, new IndexSettings());
  }

  @Override
  public ElasticsearchClient esClient() {
    return null;
  }

  @Override
  public OpenSearchClient osClient() {
    return osClient;
  }
}
