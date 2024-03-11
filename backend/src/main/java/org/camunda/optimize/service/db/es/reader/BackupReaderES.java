/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under one or more contributor license agreements.
 * Licensed under a proprietary license. See the License.txt file for more information.
 * You may not use this file except in compliance with the proprietary license.
 */
package org.camunda.optimize.service.db.es.reader;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static org.camunda.optimize.service.util.SnapshotUtil.REPOSITORY_MISSING_EXCEPTION_TYPE;
import static org.camunda.optimize.service.util.SnapshotUtil.SNAPSHOT_MISSING_EXCEPTION_TYPE;
import static org.camunda.optimize.service.util.SnapshotUtil.getAllWildcardedSnapshotNamesForBackupId;
import static org.camunda.optimize.service.util.SnapshotUtil.getAllWildcardedSnapshotNamesForWildcardedBackupId;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.camunda.optimize.service.db.es.OptimizeElasticsearchClient;
import org.camunda.optimize.service.db.reader.BackupReader;
import org.camunda.optimize.service.exceptions.OptimizeConfigurationException;
import org.camunda.optimize.service.exceptions.OptimizeElasticsearchConnectionException;
import org.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import org.camunda.optimize.service.exceptions.OptimizeSnapshotRepositoryNotFoundException;
import org.camunda.optimize.service.exceptions.conflict.OptimizeConflictException;
import org.camunda.optimize.service.util.SnapshotUtil;
import org.camunda.optimize.service.util.configuration.ConfigurationService;
import org.camunda.optimize.service.util.configuration.condition.ElasticSearchCondition;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesRequest;
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest;
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsResponse;
import org.elasticsearch.snapshots.SnapshotInfo;
import org.elasticsearch.transport.TransportException;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
@Slf4j
@Conditional(ElasticSearchCondition.class)
public class BackupReaderES implements BackupReader {

  private final OptimizeElasticsearchClient esClient;
  private final ConfigurationService configurationService;

  @Override
  public void validateRepositoryExistsOrFail() {
    final String repositoryName = getRepositoryName();
    if (StringUtils.isEmpty(repositoryName)) {
      final String reason =
          "Cannot trigger backup because no Elasticsearch snapshot repository name found in Optimize configuration.";
      log.error(reason);
      throw new OptimizeConfigurationException(reason);
    } else {
      final GetRepositoriesRequest getRepositoriesRequest =
          new GetRepositoriesRequest().repositories(new String[] {repositoryName});
      try {
        esClient.verifyRepositoryExists(getRepositoriesRequest);
      } catch (ElasticsearchStatusException e) {
        if (e.getDetailedMessage().contains(REPOSITORY_MISSING_EXCEPTION_TYPE)) {
          final String reason =
              String.format("No repository with name [%s] could be found.", repositoryName);
          log.error(reason, e);
          throw new OptimizeSnapshotRepositoryNotFoundException(reason);
        } else {
          final String reason =
              String.format(
                  "Error while retrieving repository with name [%s] due to an ElasticsearchStatusException.",
                  repositoryName);
          log.error(reason, e);
          throw new OptimizeRuntimeException(reason, e);
        }
      } catch (IOException | TransportException e) {
        final String reason =
            String.format(
                "Encountered an error connecting to Elasticsearch while retrieving repository with name [%s].",
                repositoryName);
        log.error(reason, e);
        throw new OptimizeElasticsearchConnectionException(reason, e);
      }
    }
  }

  @Override
  public void validateNoDuplicateBackupId(final Long backupId) {
    final List<SnapshotInfo> existingSnapshots = getOptimizeSnapshotsForBackupId(backupId);
    if (!existingSnapshots.isEmpty()) {
      final String reason =
          String.format(
              "A backup with ID [%s] already exists. Found snapshots: [%s]",
              backupId,
              existingSnapshots.stream()
                  .map(snapshotInfo -> snapshotInfo.snapshotId().toString())
                  .collect(joining(", ")));
      log.error(reason);
      throw new OptimizeConflictException(reason);
    }
  }

  @Override
  public Map<Long, List<SnapshotInfo>> getAllOptimizeSnapshotsByBackupId() {
    return getAllOptimizeSnapshots().stream()
        .collect(
            groupingBy(
                snapshotInfo ->
                    SnapshotUtil.getBackupIdFromSnapshotName(
                        snapshotInfo.snapshot().getSnapshotId().getName())));
  }

  @Override
  public List<SnapshotInfo> getAllOptimizeSnapshots() {
    return getOptimizeSnapshots(getAllWildcardedSnapshotNamesForWildcardedBackupId());
  }

  @Override
  public List<SnapshotInfo> getOptimizeSnapshotsForBackupId(final Long backupId) {
    return getOptimizeSnapshots(getAllWildcardedSnapshotNamesForBackupId(backupId));
  }

  private List<SnapshotInfo> getOptimizeSnapshots(final String[] snapshots) {
    final GetSnapshotsRequest snapshotsStatusRequest =
        new GetSnapshotsRequest().repository(getRepositoryName()).snapshots(snapshots);
    GetSnapshotsResponse response;
    try {
      response = esClient.getSnapshots(snapshotsStatusRequest);
    } catch (ElasticsearchStatusException e) {
      if (e.getDetailedMessage().contains(SNAPSHOT_MISSING_EXCEPTION_TYPE)) {
        // no snapshot with given backupID exists
        return Collections.emptyList();
      }
      final String reason =
          String.format(
              "Could not retrieve snapshots with names [%s] due to an ElasticsearchStatusException.",
              String.join(", ", snapshots));
      log.error(reason);
      throw new OptimizeRuntimeException(reason, e);
    } catch (IOException | TransportException e) {
      final String reason =
          String.format(
              "Encountered an error connecting to Elasticsearch while retrieving snapshots with names [%s].",
              String.join(", ", snapshots));
      log.error(reason, e);
      throw new OptimizeElasticsearchConnectionException(reason, e);
    }
    return response.getSnapshots();
  }

  private String getRepositoryName() {
    return configurationService.getElasticSearchConfiguration().getSnapshotRepositoryName();
  }
}
