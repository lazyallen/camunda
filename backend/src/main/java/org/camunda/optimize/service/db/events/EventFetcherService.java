/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.events;

import java.util.List;
import org.camunda.optimize.dto.optimize.query.event.process.EventProcessEventDto;

public interface EventFetcherService<T extends EventProcessEventDto> {

  List<T> getEventsIngestedAfter(Long eventTimestamp, int limit);

  List<T> getEventsIngestedAt(Long eventTimestamp);
}
