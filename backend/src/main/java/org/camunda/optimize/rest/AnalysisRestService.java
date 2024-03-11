/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.rest;

import static org.camunda.optimize.rest.util.TimeZoneUtil.extractTimezone;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.camunda.optimize.dto.optimize.query.analysis.BranchAnalysisRequestDto;
import org.camunda.optimize.dto.optimize.query.analysis.BranchAnalysisResponseDto;
import org.camunda.optimize.dto.optimize.query.analysis.DurationChartEntryDto;
import org.camunda.optimize.dto.optimize.query.analysis.FindingsDto;
import org.camunda.optimize.dto.optimize.query.analysis.FlowNodeOutlierParametersDto;
import org.camunda.optimize.dto.optimize.query.analysis.FlowNodeOutlierVariableParametersDto;
import org.camunda.optimize.dto.optimize.query.analysis.OutlierAnalysisServiceParameters;
import org.camunda.optimize.dto.optimize.query.analysis.ProcessDefinitionParametersDto;
import org.camunda.optimize.dto.optimize.query.analysis.VariableTermDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.FilterApplicationLevel;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.ProcessFilterDto;
import org.camunda.optimize.service.BranchAnalysisService;
import org.camunda.optimize.service.OutlierAnalysisService;
import org.camunda.optimize.service.export.CSVUtils;
import org.camunda.optimize.service.security.SessionService;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Path("/analysis")
public class AnalysisRestService {

  private final BranchAnalysisService branchAnalysisService;
  private final OutlierAnalysisService outlierAnalysisService;
  private final SessionService sessionService;

  @POST
  @Path("/correlation")
  @Produces(MediaType.APPLICATION_JSON)
  @Consumes(MediaType.APPLICATION_JSON)
  public BranchAnalysisResponseDto getBranchAnalysis(
      @Context ContainerRequestContext requestContext, BranchAnalysisRequestDto branchAnalysisDto) {
    String userId = sessionService.getRequestUserOrFailNotAuthorized(requestContext);
    final ZoneId timezone = extractTimezone(requestContext);
    return branchAnalysisService.branchAnalysis(userId, branchAnalysisDto, timezone);
  }

  @POST
  @Path("/flowNodeOutliers")
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, FindingsDto> getFlowNodeOutlierMap(
      @Context ContainerRequestContext requestContext, ProcessDefinitionParametersDto parameters) {
    String userId = sessionService.getRequestUserOrFailNotAuthorized(requestContext);
    validateProvidedFilters(parameters.getFilters());
    final OutlierAnalysisServiceParameters<ProcessDefinitionParametersDto> outlierAnalysisParams =
        new OutlierAnalysisServiceParameters<>(parameters, extractTimezone(requestContext), userId);
    final Map<String, FindingsDto> flowNodeOutlierMap =
        outlierAnalysisService.getFlowNodeOutlierMap(outlierAnalysisParams);
    final List<Map.Entry<String, FindingsDto>> sortedFindings =
        flowNodeOutlierMap.entrySet().stream()
            .sorted(
                Comparator.comparing(
                    entry ->
                        entry
                            .getValue()
                            .getHigherOutlier()
                            .map(FindingsDto.Finding::getCount)
                            .orElse(0L),
                    Comparator.reverseOrder()))
            .toList();
    final LinkedHashMap<String, FindingsDto> descendingFindings = new LinkedHashMap<>();
    for (Map.Entry<String, FindingsDto> finding : sortedFindings) {
      descendingFindings.put(finding.getKey(), finding.getValue());
    }
    return descendingFindings;
  }

  @POST
  @Path("/durationChart")
  @Produces(MediaType.APPLICATION_JSON)
  public List<DurationChartEntryDto> getCountByDurationChart(
      @Context ContainerRequestContext requestContext, FlowNodeOutlierParametersDto parameters) {
    String userId = sessionService.getRequestUserOrFailNotAuthorized(requestContext);
    validateProvidedFilters(parameters.getFilters());
    final OutlierAnalysisServiceParameters<FlowNodeOutlierParametersDto> outlierAnalysisParams =
        new OutlierAnalysisServiceParameters<>(parameters, extractTimezone(requestContext), userId);
    return outlierAnalysisService.getCountByDurationChart(outlierAnalysisParams);
  }

  @POST
  @Path("/significantOutlierVariableTerms")
  @Produces(MediaType.APPLICATION_JSON)
  public List<VariableTermDto> getSignificantOutlierVariableTerms(
      @Context ContainerRequestContext requestContext, FlowNodeOutlierParametersDto parameters) {
    String userId = sessionService.getRequestUserOrFailNotAuthorized(requestContext);
    validateProvidedFilters(parameters.getFilters());
    final OutlierAnalysisServiceParameters<FlowNodeOutlierParametersDto> outlierAnalysisParams =
        new OutlierAnalysisServiceParameters<>(parameters, extractTimezone(requestContext), userId);
    return outlierAnalysisService.getSignificantOutlierVariableTerms(outlierAnalysisParams);
  }

  @POST
  @Path("/significantOutlierVariableTerms/processInstanceIdsExport")
  // octet stream on success, json on potential error
  @Produces(value = {MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON})
  public Response getSignificantOutlierVariableTermsInstanceIds(
      @Context ContainerRequestContext requestContext,
      @PathParam("fileName") String fileName,
      FlowNodeOutlierVariableParametersDto parameters) {
    final String userId = sessionService.getRequestUserOrFailNotAuthorized(requestContext);
    validateProvidedFilters(parameters.getFilters());
    final String resultFileName = fileName == null ? System.currentTimeMillis() + ".csv" : fileName;
    final OutlierAnalysisServiceParameters<FlowNodeOutlierVariableParametersDto>
        outlierAnalysisParams =
            new OutlierAnalysisServiceParameters<>(
                parameters, extractTimezone(requestContext), userId);
    final List<String[]> processInstanceIdsCsv =
        CSVUtils.mapIdList(
            outlierAnalysisService.getSignificantOutlierVariableTermsInstanceIds(
                outlierAnalysisParams));

    return Response.ok(
            CSVUtils.mapCsvLinesToCsvBytes(processInstanceIdsCsv, ','),
            MediaType.APPLICATION_OCTET_STREAM)
        .header("Content-Disposition", "attachment; filename=" + resultFileName)
        .build();
  }

  private void validateProvidedFilters(final List<ProcessFilterDto<?>> filters) {
    if (filters.stream()
        .anyMatch(filter -> FilterApplicationLevel.VIEW == filter.getFilterLevel())) {
      throw new BadRequestException("View level filters cannot be applied during analysis");
    }
  }
}
