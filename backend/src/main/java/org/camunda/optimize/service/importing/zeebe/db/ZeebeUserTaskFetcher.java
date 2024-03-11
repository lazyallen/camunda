/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.importing.zeebe.db;

import java.util.List;
import org.camunda.optimize.dto.zeebe.usertask.ZeebeUserTaskRecordDto;
import org.camunda.optimize.service.importing.page.PositionBasedImportPage;

public interface ZeebeUserTaskFetcher extends ZeebeFetcher {
  List<ZeebeUserTaskRecordDto> getZeebeRecordsForPrefixAndPartitionFrom(
      PositionBasedImportPage positionBasedImportPage);
}
