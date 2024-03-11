/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.events.rollover;

import static org.camunda.optimize.service.db.DatabaseConstants.EXTERNAL_PROCESS_VARIABLE_INDEX_NAME;

import java.util.Collections;
import java.util.Set;
import org.camunda.optimize.service.db.DatabaseClient;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.springframework.stereotype.Component;

@Component
public class ExternalProcessVariableIndexRolloverService extends AbstractIndexRolloverService {

  private final ConfigurationService configurationService;

  protected ExternalProcessVariableIndexRolloverService(
      final DatabaseClient databaseClient, final ConfigurationService configurationService) {
    super(databaseClient);
    this.configurationService = configurationService;
  }

  @Override
  protected Set<String> getAliasesToConsiderRolling() {
    return Collections.singleton(EXTERNAL_PROCESS_VARIABLE_INDEX_NAME);
  }

  @Override
  protected int getMaxIndexSizeGB() {
    return configurationService.getVariableIndexRolloverConfiguration().getMaxIndexSizeGB();
  }

  @Override
  protected int getScheduleIntervalInMinutes() {
    return configurationService
        .getVariableIndexRolloverConfiguration()
        .getScheduleIntervalInMinutes();
  }
}
