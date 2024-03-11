/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.importing.job;

import java.util.ArrayList;
import java.util.List;
import org.camunda.optimize.dto.optimize.ImportRequestDto;
import org.camunda.optimize.dto.optimize.persistence.incident.IncidentDto;
import org.camunda.optimize.service.db.DatabaseClient;
import org.camunda.optimize.service.db.writer.incident.CompletedIncidentWriter;
import org.camunda.optimize.service.importing.DatabaseImportJob;
import org.camunda.optimize.service.util.configuration.ConfigurationService;

public class CompletedIncidentDatabaseImportJob extends DatabaseImportJob<IncidentDto> {

  private final CompletedIncidentWriter completedIncidentWriter;
  private final ConfigurationService configurationService;

  public CompletedIncidentDatabaseImportJob(
      final CompletedIncidentWriter completedIncidentWriter,
      final ConfigurationService configurationService,
      final Runnable callback,
      final DatabaseClient databaseClient) {
    super(callback, databaseClient);
    this.completedIncidentWriter = completedIncidentWriter;
    this.configurationService = configurationService;
  }

  @Override
  protected void persistEntities(List<IncidentDto> newOptimizeEntities) {
    final List<ImportRequestDto> importRequests =
        new ArrayList<>(completedIncidentWriter.generateIncidentImports(newOptimizeEntities));
    databaseClient.executeImportRequestsAsBulk(
        "Completed incidents",
        importRequests,
        configurationService.getSkipDataAfterNestedDocLimitReached());
  }
}
