/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.rest;

import static org.camunda.optimize.rest.HealthRestService.READYZ_PATH;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import lombok.AllArgsConstructor;
import org.camunda.optimize.service.status.StatusCheckingService;
import org.springframework.stereotype.Component;

@AllArgsConstructor
@Component
@Path(READYZ_PATH)
public class HealthRestService {

  public static final String READYZ_PATH = "/readyz";

  private final StatusCheckingService statusCheckingService;

  @GET
  public Response getImportStatus() {
    if (statusCheckingService.isConnectedToDatabase()
        && statusCheckingService.isConnectedToAtLeastOnePlatformEngineOrCloud()) {
      return Response.ok().build();
    }
    return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
  }
}
