/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.os.writer;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.camunda.optimize.service.db.writer.ArchiveProcessInstanceWriter;
import org.camunda.optimize.service.util.configuration.condition.OpenSearchCondition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.Set;

@AllArgsConstructor
@Component
@Slf4j
@Conditional(OpenSearchCondition.class)
public class ArchiveProcessInstanceWriterOS implements ArchiveProcessInstanceWriter {

  @Override
  public void createInstanceIndicesIfMissing(final Set<String> processDefinitionKeys) {
    //todo will be handled in the OPT-7376
  }

  @Override
  public void reloadConfiguration(final ApplicationContext context) {
    //todo will be handled in the OPT-7376
  }

}
