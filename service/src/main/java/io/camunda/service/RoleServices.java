/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.service;

import io.camunda.search.clients.RoleSearchClient;
import io.camunda.search.entities.RoleEntity;
import io.camunda.search.exception.CamundaSearchException;
import io.camunda.search.exception.NotFoundException;
import io.camunda.search.query.RoleQuery;
import io.camunda.search.query.SearchQueryBuilders;
import io.camunda.search.query.SearchQueryResult;
import io.camunda.search.security.auth.Authentication;
import io.camunda.service.search.core.SearchQueryService;
import io.camunda.zeebe.broker.client.api.BrokerClient;
import io.camunda.zeebe.gateway.impl.broker.request.role.BrokerRoleCreateRequest;
import io.camunda.zeebe.protocol.impl.record.value.authorization.RoleRecord;
import java.util.concurrent.CompletableFuture;

public class RoleServices extends SearchQueryService<RoleServices, RoleQuery, RoleEntity> {

  private final RoleSearchClient roleSearchClient;

  public RoleServices(
      final BrokerClient brokerClient,
      final RoleSearchClient roleSearchClient,
      final Authentication authentication) {
    super(brokerClient, authentication);
    this.roleSearchClient = roleSearchClient;
  }

  @Override
  public SearchQueryResult<RoleEntity> search(final RoleQuery query) {
    return roleSearchClient.searchRoles(query, authentication);
  }

  @Override
  public RoleServices withAuthentication(final Authentication authentication) {
    return new RoleServices(brokerClient, roleSearchClient, authentication);
  }

  public CompletableFuture<RoleRecord> createRole(final RoleDTO request) {
    return sendBrokerRequest(new BrokerRoleCreateRequest().setName(request.name()));
  }

  public RoleEntity getByRoleKey(final Long roleKey) {
    final SearchQueryResult<RoleEntity> result =
        search(SearchQueryBuilders.roleSearchQuery().filter(f -> f.roleKey(roleKey)).build());
    if (result.total() < 1) {
      throw new NotFoundException(String.format("Role with roleKey %d not found", roleKey));
    } else if (result.total() > 1) {
      throw new CamundaSearchException(
          String.format("Found role with roleKey %d more than once", roleKey));
    } else {
      return result.items().stream().findFirst().orElseThrow();
    }
  }

  public record RoleDTO(long roleKey, String name, long entityKey) {}
}
