/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.relations;

import java.util.Set;
import org.camunda.optimize.dto.optimize.query.collection.CollectionDefinitionDto;
import org.camunda.optimize.dto.optimize.rest.ConflictedItemDto;

public interface CollectionReferencingService {
  Set<ConflictedItemDto> getConflictedItemsForCollectionDelete(CollectionDefinitionDto definition);

  void handleCollectionDeleted(CollectionDefinitionDto definition);
}
