/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.test.util;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.tdunning.math.stats.TDigest;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.commons.math3.util.Precision;
import org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationDto;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.math.Quantiles.percentiles;
import static org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationType.AVERAGE;
import static org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationType.MAX;
import static org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationType.MIN;
import static org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationType.PERCENTILE;
import static org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationType.SUM;
import static org.camunda.optimize.dto.optimize.query.report.single.configuration.AggregationType.values;

public class DurationAggregationUtil {

  public static Double calculateExpectedValueGivenDurationsDefaultAggr(final Number setDuration) {
    return Optional.ofNullable(setDuration)
      .map(DurationAggregationUtil::calculateExpectedValueGivenDurationsWithoutPercentileInterpolation)
      .map(stats -> stats.get(new AggregationDto(AVERAGE)))
      .orElse(null);
  }

  public static Double calculateExpectedValueGivenDurationsDefaultAggr(final Number... setDuration) {
    final double aggregatedDuration = calculateExpectedValueGivenDurationsWithoutPercentileInterpolation(setDuration)
      .get(new AggregationDto(AVERAGE));
    // for duration, we should omit the decimal numbers since it's not relevant for the user
    return Precision.round(aggregatedDuration, 0);
  }

  public static AggregationDto[] getSupportedAggregationTypes() {
    List<AggregationDto> aggregationDtos = Arrays.stream(values())
      .filter(aggType -> !aggType.equals(PERCENTILE))
      .map(AggregationDto::new)
      .collect(Collectors.toList());
    aggregationDtos.addAll(List.of(
      new AggregationDto(PERCENTILE, 99.),
      new AggregationDto(PERCENTILE, 95.),
      new AggregationDto(PERCENTILE, 75.),
      new AggregationDto(PERCENTILE, 50.),
      new AggregationDto(PERCENTILE, 25.)
    ));
    return aggregationDtos.toArray(AggregationDto[]::new);
  }

  public static AggregationDto[] getAggregationTypesAsListForProcessParts() {
    // process parts does not support percentile since it does the result calculation
    // with a script and the script does not allow sorting over all values.
    return Arrays.stream(values())
      .filter(type -> !ImmutableSet.of(PERCENTILE).contains(type))
      .map(AggregationDto::new)
      .toArray(AggregationDto[]::new);
  }

  public static Map<AggregationDto, Double> calculateExpectedValueGivenDurationsWithoutPercentileInterpolation(final Number... setDuration) {
    final TDigest tDigest = TDigest.createAvlTreeDigest(100);
    Arrays.stream(setDuration).forEach(duration -> tDigest.add((double) duration));
    return calculateExpectedValueGivenDurationsUsingPercentilesFunction(
      q -> Precision.round(tDigest.quantile(q), 0), setDuration);
  }

  public static Map<AggregationDto, Double> calculateExpectedValueGivenDurationsWithPercentileInterpolation(final Number... setDuration) {
    return calculateExpectedValueGivenDurationsUsingPercentilesFunction(
      percentile -> percentiles().index((int) (percentile * 100)).compute(Arrays.asList(setDuration)),
      setDuration
    );
  }

  private static Map<AggregationDto, Double> calculateExpectedValueGivenDurationsUsingPercentilesFunction(
    final Function<Double, Double> percentileFunction,
    final Number... setDuration) {
    final DescriptiveStatistics statistics = new DescriptiveStatistics();
    Stream.of(setDuration).map(Number::longValue).forEach(statistics::addValue);

    return ImmutableMap.of(
      new AggregationDto(MIN), Precision.round(statistics.getMin(), 0),
      new AggregationDto(MAX), Precision.round(statistics.getMax(), 0),
      new AggregationDto(AVERAGE), Precision.round(statistics.getMean(), 0),
      new AggregationDto(SUM), Precision.round(statistics.getSum(), 0),
      new AggregationDto(PERCENTILE, 99.), percentileFunction.apply(0.99),
      new AggregationDto(PERCENTILE, 95.), percentileFunction.apply(0.95),
      new AggregationDto(PERCENTILE, 75.), percentileFunction.apply(0.75),
      new AggregationDto(PERCENTILE, 50.), percentileFunction.apply(0.50),
      new AggregationDto(PERCENTILE, 25.), percentileFunction.apply(0.25)
    );
  }

}
