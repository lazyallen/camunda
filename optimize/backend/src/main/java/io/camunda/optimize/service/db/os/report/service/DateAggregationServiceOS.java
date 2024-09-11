/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.db.os.report.service;

import static io.camunda.optimize.rest.util.TimeZoneUtil.formatToCorrectTimezone;
import static io.camunda.optimize.service.db.DatabaseConstants.OPTIMIZE_DATE_FORMAT;
import static io.camunda.optimize.service.db.os.externalcode.client.dsl.AggregationDSL.fieldDateMath;
import static io.camunda.optimize.service.db.os.externalcode.client.dsl.QueryDSL.filter;
import static io.camunda.optimize.service.db.os.externalcode.client.dsl.QueryDSL.matchAll;
import static io.camunda.optimize.service.db.os.report.filter.util.DateHistogramFilterUtilOS.createDecisionDateHistogramLimitingFilter;
import static io.camunda.optimize.service.db.os.report.filter.util.DateHistogramFilterUtilOS.createFilterBoolQueryBuilder;
import static io.camunda.optimize.service.db.os.report.filter.util.DateHistogramFilterUtilOS.extendBounds;
import static io.camunda.optimize.service.db.os.report.filter.util.DateHistogramFilterUtilOS.getExtendedBoundsFromDateFilters;
import static io.camunda.optimize.service.db.os.report.interpreter.util.AggregateByDateUnitMapperOS.mapToCalendarInterval;
import static io.camunda.optimize.service.db.os.report.interpreter.util.FilterLimitedAggregationUtilOS.wrapWithFilterLimitedParentAggregation;
import static io.camunda.optimize.service.db.report.service.DateAggregationService.getDateHistogramIntervalDurationFromMinMax;

import io.camunda.optimize.dto.optimize.query.report.single.filter.data.date.DateFilterDataDto;
import io.camunda.optimize.dto.optimize.query.report.single.group.AggregateByDateUnit;
import io.camunda.optimize.dto.optimize.query.report.single.process.filter.InstanceEndDateFilterDto;
import io.camunda.optimize.dto.optimize.query.report.single.process.filter.InstanceStartDateFilterDto;
import io.camunda.optimize.service.db.os.report.context.DateAggregationContextOS;
import io.camunda.optimize.service.db.os.report.filter.ProcessQueryFilterEnhancerOS;
import io.camunda.optimize.service.db.os.report.interpreter.util.FilterLimitedAggregationUtilOS;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch._types.aggregations.Aggregate;
import org.opensearch.client.opensearch._types.aggregations.Aggregation;
import org.opensearch.client.opensearch._types.aggregations.DateHistogramAggregation;
import org.opensearch.client.opensearch._types.aggregations.DateHistogramBucket;
import org.opensearch.client.opensearch._types.aggregations.DateRangeExpression;
import org.opensearch.client.opensearch._types.aggregations.HistogramOrder;
import org.opensearch.client.opensearch._types.aggregations.MultiBucketBase;
import org.opensearch.client.opensearch._types.aggregations.RangeBucket;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class DateAggregationServiceOS {

  private static final String DATE_AGGREGATION = "dateAggregation";

  private final DateTimeFormatter dateTimeFormatter;

  public Optional<Pair<String, Aggregation>> createProcessInstanceDateAggregation(
      final DateAggregationContextOS context) {
    if (context.getMinMaxStats().isEmpty()) {
      // no instances present
      return Optional.empty();
    }

    if (AggregateByDateUnit.AUTOMATIC.equals(context.getAggregateByDateUnit())) {
      return Optional.of(
          createAutomaticIntervalAggregationOrFallbackToMonth(
              context, this::createFilterLimitedProcessDateHistogramWithSubAggregation));
    }
    return Optional.of(createFilterLimitedProcessDateHistogramWithSubAggregation(context));
  }

  public Optional<Pair<String, Aggregation>> createDateVariableAggregation(
      final DateAggregationContextOS context) {
    if (context.getMinMaxStats().isEmpty()) {
      // no instances present
      return Optional.empty();
    }

    if (AggregateByDateUnit.AUTOMATIC.equals(context.getAggregateByDateUnit())) {
      return Optional.of(
          createAutomaticIntervalAggregationOrFallbackToMonth(
              context, this::createDateHistogramWithSubAggregation));
    }

    return Optional.of(createDateHistogramWithSubAggregation(context));
  }

  public Optional<Pair<String, Aggregation>> createDecisionEvaluationDateAggregation(
      final DateAggregationContextOS context) {
    if (context.getMinMaxStats().isEmpty()) {
      // no instances present
      return Optional.empty();
    }

    if (AggregateByDateUnit.AUTOMATIC.equals(context.getAggregateByDateUnit())) {
      return Optional.ofNullable(
          createAutomaticIntervalAggregationOrFallbackToMonth(
              context, this::createFilterLimitedDecisionDateHistogramWithSubAggregation));
    }

    return Optional.of(createFilterLimitedDecisionDateHistogramWithSubAggregation(context));
  }

  public Map<String, Map<String, Aggregate>> mapDateAggregationsToKeyAggregationMap(
      final Map<String, Aggregate> aggregations, final ZoneId timezone) {
    return mapDateAggregationsToKeyAggregationMap(aggregations, timezone, DATE_AGGREGATION);
  }

  private Map<String, Map<String, Aggregate>> multiBucketAggregation(Aggregate aggregate) {
    if (aggregate.isDateHistogram()) {
      return aggregate.dateHistogram().buckets().array().stream()
          .collect(
              Collectors.toMap(DateHistogramBucket::keyAsString, MultiBucketBase::aggregations));
    } else if (aggregate.isDateRange()) {
      return aggregate.dateRange().buckets().array().stream()
          .collect(Collectors.toMap(RangeBucket::key, MultiBucketBase::aggregations));
    } else {
      throw new UnsupportedOperationException(
          "Unsupported multi bucket aggregation type " + aggregate._kind().name());
    }
  }

  public Map<String, Map<String, Aggregate>> mapDateAggregationsToKeyAggregationMap(
      final Map<String, Aggregate> aggregations,
      final ZoneId timezone,
      final String aggregationName) {
    return multiBucketAggregation(aggregations.get(aggregationName)).entrySet().stream()
        .map(
            entry ->
                Pair.of(
                    formatToCorrectTimezone(entry.getKey(), timezone, dateTimeFormatter),
                    entry.getValue()))
        .sorted(Collections.reverseOrder(Map.Entry.comparingByKey()))
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                (u, v) -> {
                  throw new IllegalStateException(String.format("Duplicate key %s", u));
                },
                LinkedHashMap::new));
  }

  private Pair<String, Aggregation> createDateHistogramAggregation(
      final DateAggregationContextOS context,
      Consumer<DateHistogramAggregation.Builder> dateHistogramAggregationBuilderModification) {
    DateHistogramAggregation.Builder dateHistogramAggregationBuilder =
        new DateHistogramAggregation.Builder()
            .order(b -> b.key(SortOrder.Desc))
            .field(context.getDateField())
            .calendarInterval(mapToCalendarInterval(context.getAggregateByDateUnit()))
            .format(OPTIMIZE_DATE_FORMAT)
            .timeZone(context.getTimezone().getDisplayName(TextStyle.SHORT, Locale.US));

    if (context.isExtendBoundsToMinMaxStats()
        && context.getMinMaxStats().isMaxValid()
        && context.getMinMaxStats().isMinValid()) {
      dateHistogramAggregationBuilder.extendedBounds(
          b ->
              b.min(fieldDateMath(context.getMinMaxStats().getMin()))
                  .max(fieldDateMath(context.getMinMaxStats().getMax())));
    }

    dateHistogramAggregationBuilderModification.accept(dateHistogramAggregationBuilder);

    return Pair.of(
        context.getDateAggregationName().orElse(DATE_AGGREGATION),
        new Aggregation.Builder()
            .aggregations(context.getSubAggregations())
            .dateHistogram(dateHistogramAggregationBuilder.build())
            .build());
  }

  private Pair<String, Aggregation> createDateHistogramWithSubAggregation(
      final DateAggregationContextOS context) {
    return Pair.of(
        context.getDateAggregationName().orElse(DATE_AGGREGATION),
        new Aggregation.Builder()
            .dateHistogram(
                b ->
                    b.order(HistogramOrder.of(b1 -> b1.key(SortOrder.Desc)))
                        .field(context.getDateField())
                        .calendarInterval(mapToCalendarInterval(context.getAggregateByDateUnit()))
                        .format(OPTIMIZE_DATE_FORMAT)
                        .timeZone(context.getTimezone().getDisplayName(TextStyle.SHORT, Locale.US)))
            .aggregations(context.getSubAggregations())
            .build());
  }

  private Pair<String, Aggregation> createAutomaticIntervalAggregationOrFallbackToMonth(
      final DateAggregationContextOS context,
      final Function<DateAggregationContextOS, Pair<String, Aggregation>>
          defaultAggregationCreator) {
    return createAutomaticIntervalAggregationWithSubAggregation(context)
        .map(
            automaticIntervalAggregation ->
                wrapWithFilterLimitedParentAggregation(matchAll(), automaticIntervalAggregation))
        // automatic interval not possible, return default aggregation with unit month instead
        .orElse(defaultAggregation(context, defaultAggregationCreator));
  }

  private Pair<String, Aggregation> defaultAggregation(
      final DateAggregationContextOS context,
      final Function<DateAggregationContextOS, Pair<String, Aggregation>>
          defaultAggregationCreator) {
    context.setAggregateByDateUnit(AggregateByDateUnit.MONTH);
    return defaultAggregationCreator.apply(context);
  }

  private Optional<Pair<String, Aggregation>> createAutomaticIntervalAggregationWithSubAggregation(
      final DateAggregationContextOS context) {
    if (!context.getMinMaxStats().isValidRange()) {
      return Optional.empty();
    }

    final ZonedDateTime min = context.getEarliestDate();
    final ZonedDateTime max = context.getLatestDate();

    final Duration intervalDuration =
        getDateHistogramIntervalDurationFromMinMax(context.getMinMaxStats());
    ZonedDateTime start = min;

    final List<DateRangeExpression> ranges = new ArrayList<>();
    do {
      // this is a do while loop to ensure there's always at least one bucket, even when min and max
      // are equal
      ZonedDateTime nextStart = start.plus(intervalDuration);
      boolean isLast = nextStart.isAfter(max) || nextStart.isEqual(max);
      // plus 1 ms because the end of the range is exclusive yet we want to make sure max falls into
      // the last bucket
      ZonedDateTime end = isLast ? nextStart.plus(1, ChronoUnit.MILLIS) : nextStart;

      DateRangeExpression range =
          new DateRangeExpression.Builder()
              .key(dateTimeFormatter.format(start))
              .from(fieldDateMath(dateTimeFormatter.format(start)))
              .to(fieldDateMath(dateTimeFormatter.format(end)))
              .build();

      ranges.add(range);
      start = nextStart;
    } while (start.isBefore(max));

    final String aggregationName = context.getDateAggregationName().orElse(DATE_AGGREGATION);
    final Aggregation rangeAgg =
        new Aggregation.Builder()
            .aggregations(context.getSubAggregations())
            .dateRange(
                b ->
                    b.field(context.getDateField())
                        .timeZone(min.getZone().getDisplayName(TextStyle.SHORT, Locale.US))
                        .ranges(ranges))
            .build();

    return Optional.of(Pair.of(aggregationName, rangeAgg));
  }

  private Pair<String, Aggregation> createFilterLimitedDecisionDateHistogramWithSubAggregation(
      final DateAggregationContextOS context) {
    final Pair<String, Aggregation> dateHistogramAggregation =
        createDateHistogramAggregation(
            context,
            builder -> extendBounds(context, dateTimeFormatter).ifPresent(builder::extendedBounds));
    final List<Query> limitFilterQuery = createDecisionDateHistogramLimitingFilter(context);
    return wrapWithFilterLimitedParentAggregation(
        filter(limitFilterQuery), dateHistogramAggregation);
  }

  private Pair<String, Aggregation> createFilterLimitedProcessDateHistogramWithSubAggregation(
      final DateAggregationContextOS context) {
    final Pair<String, Aggregation> dateHistogramAggregation =
        createDateHistogramAggregation(context, extendBoundsConsumer(context));
    final Query limitFilterQuery = createProcessDateHistogramLimitingFilterQuery(context);
    return FilterLimitedAggregationUtilOS.wrapWithFilterLimitedParentAggregation(
        limitFilterQuery, dateHistogramAggregation);
  }

  private Consumer<DateHistogramAggregation.Builder> extendBoundsConsumer(
      final DateAggregationContextOS context) {
    return (DateHistogramAggregation.Builder builder) -> {
      final ProcessQueryFilterEnhancerOS queryFilterEnhancer =
          context.getProcessQueryFilterEnhancer();
      final List<DateFilterDataDto<?>> dateFilters =
          context.isStartDateAggregation()
              ? queryFilterEnhancer.extractInstanceFilters(
                  context.getProcessFilters(), InstanceStartDateFilterDto.class)
              : queryFilterEnhancer.extractInstanceFilters(
                  context.getProcessFilters(), InstanceEndDateFilterDto.class);
      if (!dateFilters.isEmpty()) {
        getExtendedBoundsFromDateFilters(dateFilters, dateTimeFormatter, context)
            .ifPresent(builder::extendedBounds);
      }
    };
  }

  private static Query createProcessDateHistogramLimitingFilterQuery(
      final DateAggregationContextOS context) {
    final ProcessQueryFilterEnhancerOS queryFilterEnhancer =
        context.getProcessQueryFilterEnhancer();
    final List<DateFilterDataDto<?>> startDateFilters =
        queryFilterEnhancer.extractInstanceFilters(
            context.getProcessFilters(), InstanceStartDateFilterDto.class);
    final List<DateFilterDataDto<?>> endDateFilters =
        queryFilterEnhancer.extractInstanceFilters(
            context.getProcessFilters(), InstanceEndDateFilterDto.class);
    final List<Query> limitFilterQueries =
        context.isStartDateAggregation()
            ?
            // if custom end filters and no startDateFilters are present, limit based on them
            !endDateFilters.isEmpty() && startDateFilters.isEmpty()
                ? createFilterBoolQueryBuilder(
                    endDateFilters,
                    queryFilterEnhancer.getInstanceEndDateQueryFilter(),
                    context.getFilterContext())
                : createFilterBoolQueryBuilder(
                    startDateFilters,
                    queryFilterEnhancer.getInstanceStartDateQueryFilter(),
                    context.getFilterContext())
            :
            // if custom start filters and no endDateFilters are present, limit based on them
            endDateFilters.isEmpty() && !startDateFilters.isEmpty()
                ? createFilterBoolQueryBuilder(
                    startDateFilters,
                    queryFilterEnhancer.getInstanceStartDateQueryFilter(),
                    context.getFilterContext())
                : createFilterBoolQueryBuilder(
                    endDateFilters,
                    queryFilterEnhancer.getInstanceEndDateQueryFilter(),
                    context.getFilterContext());
    return filter(limitFilterQueries);
  }
}
