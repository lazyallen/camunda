/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.agrona.Strings;
import org.apache.commons.lang3.StringUtils;
import org.camunda.optimize.dto.optimize.query.report.single.ViewProperty;
import org.camunda.optimize.service.exceptions.OptimizeConfigurationException;
import org.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import org.camunda.optimize.service.util.configuration.ConfigurationReloadable;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.camunda.optimize.util.SuppressionConstants;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class LocalizationService implements ConfigurationReloadable {

  public static final String LOCALIZATION_PATH = "localization/";

  private static final String API_ERRORS_FIELD = "apiErrors";
  private static final String MANAGEMENT_DASHBOARD_FIELD = "managementDashboard";
  private static final String INSTANT_DASHBOARD_FIELD = "instantDashboard";
  private static final String JSON_FILE_EXTENSION = "json";
  private static final String MARKDOWN_FILE_EXTENSION = "md";
  private static final String LOCALIZATION_FILE_PREFIX_WHATSNEW = "whatsnew";
  private static final String REPORT_FIELD = "report";
  private static final String REPORT_GROUPING_FIELD = "groupBy";
  private static final String REPORT_VIEW_FIELD = "view";
  public static final String REPORT_COUNT_KEY = "count";
  public static final String REPORT_DURATION_KEY = "duration";
  private static final String MISSING_ASSIGNEE_FIELD = "missingAssignee";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConfigurationService configurationService;

  private LoadingCache<String, Map<String, Object>> localeToLocalizationMapCache;

  public LocalizationService(final ConfigurationService configurationService) {
    this.configurationService = configurationService;
    validateLocalizationSetup();
  }

  @Override
  public void reloadConfiguration(final ApplicationContext context) {
    validateLocalizationSetup();
  }

  public byte[] getLocalizationFileBytes(final String localeCode) {
    return getLocalizedJsonFile(localeCode);
  }

  public String getDefaultLocaleMessageForApiErrorCode(final String code) {
    try {
      return getMessageForApiErrorCode(configurationService.getFallbackLocale(), code);
    } catch (Exception e) {
      return String.format("Failed to localize error message for code [%s]", code);
    }
  }

  public String getDefaultLocaleMessageForMissingAssigneeLabel() {
    try {
      return getMessageForCode(
          configurationService.getFallbackLocale(), REPORT_FIELD, MISSING_ASSIGNEE_FIELD);
    } catch (Exception e) {
      return String.format(
          "Failed to localize label for missing assignee field with localization code: %s",
          MISSING_ASSIGNEE_FIELD);
    }
  }

  public byte[] getLocalizedWhatsNewMarkdown(final String localeCode) {
    return getLocalizedMarkdownFile(LOCALIZATION_FILE_PREFIX_WHATSNEW, localeCode);
  }

  public String getLocalizationForManagementDashboardCode(
      final String localeCode, final String dashboardCode) {
    return getMessageForCode(localeCode, MANAGEMENT_DASHBOARD_FIELD, dashboardCode);
  }

  public String getLocalizationForInstantPreviewDashboardCode(
      final String localeCode, final String dashboardCode) {
    return getMessageForCode(localeCode, INSTANT_DASHBOARD_FIELD, dashboardCode);
  }

  public String getLocalizationForManagementReportCode(
      final String localeCode, final String reportCode) {
    return getNestedMessageForCode(
        localeCode, MANAGEMENT_DASHBOARD_FIELD, REPORT_FIELD, reportCode);
  }

  public String getLocalizationForInstantPreviewReportCode(
      final String localeCode, final String reportCode) {
    return getNestedMessageForCode(localeCode, INSTANT_DASHBOARD_FIELD, REPORT_FIELD, reportCode);
  }

  public String getLocalizedXLabel(final String validLocale, final String xLabelKey) {
    if (Strings.isEmpty(xLabelKey)) {
      return xLabelKey;
    }
    final Map<String, Map<String, String>> reportLocalistionMap =
        getNestedLocalizationMap(validLocale, REPORT_FIELD);
    final Map<String, String> reportGroupByMap = reportLocalistionMap.get(REPORT_GROUPING_FIELD);
    return reportGroupByMap.get(xLabelKey);
  }

  public String getLocalizedYLabel(
      final String validLocale, final String yLabel, final ViewProperty view) {
    if (Strings.isEmpty(yLabel)) {
      return yLabel;
    }
    final Map<String, Map<String, String>> reportLocalistionMap =
        getNestedLocalizationMap(validLocale, REPORT_FIELD);
    final Map<String, String> reportGroupByMap = reportLocalistionMap.get(REPORT_VIEW_FIELD);
    final String localizedReportView = reportGroupByMap.get(yLabel);
    if (view.equals(ViewProperty.FREQUENCY)) {
      return localizedReportView + " " + reportGroupByMap.get(REPORT_COUNT_KEY);
    } else if (view.equals(ViewProperty.DURATION)) {
      return localizedReportView + " " + reportGroupByMap.get(REPORT_DURATION_KEY);
    } else {
      return localizedReportView;
    }
  }

  public String validateAndReturnValidLocale(final String locale) {
    if (configurationService.getAvailableLocales().contains(locale)) {
      return locale;
    } else {
      final String fallbackLocale = configurationService.getFallbackLocale();
      log.error(
          "No valid localization files found for given locale [{}]. Defaulting to fallback locale [{}] instead.",
          locale,
          fallbackLocale);
      return fallbackLocale;
    }
  }

  private void validateLocalizationFiles() {
    configurationService
        .getAvailableLocales()
        .forEach(
            locale -> {
              final String filePath = resolveFilePath(JSON_FILE_EXTENSION, locale);
              validateFileExists(filePath);
              validateJsonFile(filePath);
            });
  }

  private void validateMarkdownFiles() {
    configurationService
        .getAvailableLocales()
        .forEach(
            locale -> {
              final String filePath =
                  resolveFilePath(
                      LOCALIZATION_FILE_PREFIX_WHATSNEW, MARKDOWN_FILE_EXTENSION, locale);
              validateFileExists(filePath);
            });
  }

  private void validateFileExists(final String fileName) {
    final URL localizationFile = getClass().getClassLoader().getResource(fileName);
    if (localizationFile == null) {
      throw new OptimizeConfigurationException(
          String.format("File for configured availableLocale is not available [%s].", fileName));
    }
  }

  private void validateFallbackLocale() {
    if (!configurationService
        .getAvailableLocales()
        .contains(configurationService.getFallbackLocale())) {
      final String message =
          String.format(
              "The configured fallbackLocale [%s] is not present in availableLocales %s.",
              configurationService.getFallbackLocale(),
              configurationService.getAvailableLocales().toString());
      throw new OptimizeConfigurationException(message);
    }
  }

  private void validateJsonFile(final String fileName) {
    final URL localizationFile = getClass().getClassLoader().getResource(fileName);
    try {
      objectMapper.readTree(localizationFile);
    } catch (IOException e) {
      throw new OptimizeConfigurationException(
          String.format(
              "File for configured availableLocale is not a valid JSON file [%s].", fileName),
          e);
    }
  }

  private byte[] getLocalizedJsonFile(final String localeCode) {
    return getLocalizedFile(null, JSON_FILE_EXTENSION, localeCode);
  }

  private byte[] getLocalizedMarkdownFile(final String key, final String localeCode) {
    return getLocalizedFile(key, MARKDOWN_FILE_EXTENSION, localeCode);
  }

  private byte[] getLocalizedFile(
      final String filePrefix, final String fileExtension, final String localeCode) {
    final String resolvedLocaleCode =
        configurationService.getAvailableLocales().contains(localeCode)
            ? localeCode
            : configurationService.getFallbackLocale();

    return getFileBytes(resolveFilePath(filePrefix, fileExtension, resolvedLocaleCode));
  }

  private String resolveFilePath(final String fileExtension, final String localeCode) {
    return resolveFilePath(null, fileExtension, localeCode);
  }

  private String resolveFilePath(
      final String filePrefix, final String fileExtension, final String localeCode) {
    final String prefix = StringUtils.isNotBlank(filePrefix) ? filePrefix + "_" : "";
    return LOCALIZATION_PATH + prefix + localeCode + "." + fileExtension;
  }

  private byte[] getFileBytes(final String localeFileName) {
    Optional<byte[]> result = Optional.empty();

    try (final InputStream localeFileAsStream =
        getClass().getClassLoader().getResourceAsStream(localeFileName)) {
      result =
          Optional.ofNullable(localeFileAsStream)
              .flatMap(
                  inputStream -> {
                    try {
                      return Optional.of(ByteStreams.toByteArray(inputStream));
                    } catch (final IOException e) {
                      log.error(
                          "Failed reading localization file {} from classpath", localeFileName, e);
                      return Optional.empty();
                    }
                  });
    } catch (final IOException e) {
      log.debug("Failed closing stream of locale file {}.", localeFileName, e);
    }
    return result.orElseThrow(
        () -> new OptimizeRuntimeException("Could not load localization file: " + localeFileName));
  }

  private String getMessageForApiErrorCode(final String localeCode, final String errorCode) {
    return getMessageForCode(localeCode, API_ERRORS_FIELD, errorCode);
  }

  @SuppressWarnings(SuppressionConstants.UNCHECKED_CAST)
  private String getMessageForCode(
      final String localeCode, final String categoryCode, final String messageCode) {
    final Map<String, String> categoryMessageCodeMap =
        (Map<String, String>) localeToLocalizationMapCache.get(localeCode).get(categoryCode);
    return categoryMessageCodeMap.get(messageCode);
  }

  private String getNestedMessageForCode(
      final String localeCode,
      final String categoryCode,
      final String subcategoryCode,
      final String messageCode) {
    final Map<String, Map<String, String>> categoryMessageCodeMap =
        getNestedLocalizationMap(localeCode, categoryCode);
    return categoryMessageCodeMap.get(subcategoryCode).get(messageCode);
  }

  @SuppressWarnings(SuppressionConstants.UNCHECKED_CAST)
  private Map<String, Map<String, String>> getNestedLocalizationMap(
      final String localeCode, final String categoryCode) {
    return (Map<String, Map<String, String>>)
        localeToLocalizationMapCache.get(localeCode).get(categoryCode);
  }

  private void validateLocalizationSetup() {
    validateLocalizationFiles();
    validateMarkdownFiles();
    validateFallbackLocale();
    localeToLocalizationMapCache =
        Caffeine.newBuilder()
            .maximumSize(5)
            .expireAfterAccess(30, TimeUnit.MINUTES)
            // @formatter:off
            .build(
                localeCode ->
                    objectMapper.readValue(
                        getLocalizedJsonFile(localeCode), new TypeReference<>() {}));
    // @formatter:on
  }
}
