/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.os.reader;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.optimize.dto.optimize.query.alert.AlertDefinitionDto;
import org.camunda.optimize.service.db.reader.AlertReader;
import org.camunda.optimize.service.db.os.OptimizeOpenSearchClient;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.camunda.optimize.service.util.configuration.condition.OpenSearchCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
@Slf4j
@Conditional(OpenSearchCondition.class)
public class AlertReaderOS implements AlertReader {

  private final OptimizeOpenSearchClient osClient;
  private final ConfigurationService configurationService;

  @Override
  public long getAlertCount() {
    //todo will be handled in the OPT-7230
    return 1L;
  }

  @Override
  public List<AlertDefinitionDto> getStoredAlerts() {
    //todo will be handled in the OPT-7230
    return new ArrayList<>();
  }

  @Override
  public Optional<AlertDefinitionDto> getAlert(String alertId) {
    //todo will be handled in the OPT-7230
    return Optional.empty();
  }

  @Override
  public List<AlertDefinitionDto> getAlertsForReport(String reportId) {
    //todo will be handled in the OPT-7230
    return new ArrayList<>();
  }

  @Override
  public List<AlertDefinitionDto> getAlertsForReports(List<String> reportIds) {
    //todo will be handled in the OPT-7230
    return new ArrayList<>();
  }

  private void logError(String alertId) {
    log.error("Was not able to retrieve alert with id [{}] from Elasticsearch.", alertId);
  }

}
