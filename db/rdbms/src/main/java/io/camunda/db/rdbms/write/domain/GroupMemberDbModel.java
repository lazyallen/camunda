/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.db.rdbms.write.domain;

import io.camunda.util.ObjectBuilder;

public record GroupMemberDbModel(Long groupKey, Long entityKey, String entityType) {

  // create builder implementing ObjectBuilder
  public static class Builder implements ObjectBuilder<GroupMemberDbModel> {

    private Long groupKey;
    private Long entityKey;
    private String entityType;

    public Builder groupKey(final Long groupKey) {
      this.groupKey = groupKey;
      return this;
    }

    public Builder entityKey(final Long entityKey) {
      this.entityKey = entityKey;
      return this;
    }

    public Builder entityType(final String entityType) {
      this.entityType = entityType;
      return this;
    }

    @Override
    public GroupMemberDbModel build() {
      return new GroupMemberDbModel(groupKey, entityKey, entityType);
    }
  }
}
