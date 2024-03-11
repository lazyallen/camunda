/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.es.schema.index;

import java.io.IOException;
import org.camunda.optimize.service.db.schema.index.DashboardIndex;
import org.elasticsearch.xcontent.XContentBuilder;

public class DashboardIndexES extends DashboardIndex<XContentBuilder> {

  @Override
  public XContentBuilder addStaticSetting(
      final String key, final int value, final XContentBuilder contentBuilder) throws IOException {
    return contentBuilder.field(key, value);
  }
}
