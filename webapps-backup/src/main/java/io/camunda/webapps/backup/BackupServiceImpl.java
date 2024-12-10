/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.webapps.backup;

import io.camunda.webapps.backup.BackupException.*;
import io.camunda.webapps.backup.repository.BackupRepositoryProps;
import io.camunda.webapps.schema.descriptors.backup.BackupPriorities;
import io.camunda.webapps.schema.descriptors.backup.BackupPriority;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BackupServiceImpl implements BackupService {

  private static final Logger LOGGER = LoggerFactory.getLogger(BackupServiceImpl.class);
  private final Executor threadPoolTaskExecutor;
  private final Queue<SnapshotRequest> requestsQueue = new ConcurrentLinkedQueue<>();

  private final BackupPriorities backupPriorities;
  private final BackupRepositoryProps backupProps;

  private final BackupRepository repository;

  private String[][] indexPatternsOrdered;

  public BackupServiceImpl(
      final Executor threadPoolTaskExecutor,
      final BackupPriorities backupPriorities,
      final BackupRepositoryProps operateProperties,
      final BackupRepository repository) {
    this.threadPoolTaskExecutor = threadPoolTaskExecutor;
    this.backupPriorities = backupPriorities;
    this.repository = repository;
    backupProps = operateProperties;
  }

  @Override
  public void deleteBackup(final Long backupId) {
    repository.validateRepositoryExists(backupProps.repositoryName());
    final String repositoryName = backupProps.repositoryName();
    final int count = getIndexPatternsOrdered().length;
    final String version = getCurrentVersion();
    for (int index = 0; index < count; index++) {
      final String snapshotName =
          repository
              .snapshotNameProvider()
              .getSnapshotName(new Metadata(backupId, version, index + 1, count));
      repository.deleteSnapshot(repositoryName, snapshotName);
    }
  }

  @Override
  public TakeBackupResponseDto takeBackup(final TakeBackupRequestDto request) {
    repository.validateRepositoryExists(backupProps.repositoryName());
    repository.validateNoDuplicateBackupId(backupProps.repositoryName(), request.getBackupId());
    if (!requestsQueue.isEmpty()) {
      throw new InvalidRequestException("Another backup is running at the moment");
    } // TODO remove duplicate
    synchronized (requestsQueue) {
      if (!requestsQueue.isEmpty()) {
        throw new InvalidRequestException("Another backup is running at the moment");
      }
      return scheduleSnapshots(request);
    }
  }

  @Override
  public List<GetBackupStateResponseDto> getBackups() {
    return repository.getBackups(backupProps.repositoryName());
  }

  @Override
  public GetBackupStateResponseDto getBackupState(final Long backupId) {
    return repository.getBackupState(backupProps.repositoryName(), backupId);
  }

  TakeBackupResponseDto scheduleSnapshots(final TakeBackupRequestDto request) {
    final String repositoryName = backupProps.repositoryName();
    final int count = getIndexPatternsOrdered().length;
    final List<String> snapshotNames = new ArrayList<>();
    final String version = getCurrentVersion();
    for (int index = 0; index < count; index++) {
      final List<String> indexPattern = Arrays.asList(getIndexPatternsOrdered()[index]);
      final Metadata metadata = new Metadata(request.getBackupId(), version, index + 1, count);
      final String snapshotName = repository.snapshotNameProvider().getSnapshotName(metadata);
      final SnapshotRequest snapshotRequest =
          new SnapshotRequest(repositoryName, snapshotName, indexPattern, metadata);

      requestsQueue.offer(snapshotRequest);
      LOGGER.debug("Snapshot scheduled: {}", snapshotName);
      snapshotNames.add(snapshotName);
    }
    // schedule next snapshot
    scheduleNextSnapshot();
    return new TakeBackupResponseDto().setScheduledSnapshots(snapshotNames);
  }

  void scheduleNextSnapshot() {
    final SnapshotRequest nextRequest = requestsQueue.poll();
    if (nextRequest != null) {
      threadPoolTaskExecutor.execute(
          () ->
              repository.executeSnapshotting(
                  nextRequest, this::scheduleNextSnapshot, requestsQueue::clear));
      LOGGER.debug("Snapshot picked for execution: {}", nextRequest);
    }
  }

  private String[][] getIndexPatternsOrdered() {
    if (indexPatternsOrdered == null) {
      indexPatternsOrdered =
          new String[][] {
            fullQualifiedName(backupPriorities.prio1()),
            fullQualifiedName(backupPriorities.prio2()),
            // dated indices
            fullQualifiedNameWithMatcher(backupPriorities.prio2()),
            fullQualifiedName(backupPriorities.prio3()),
            fullQualifiedName(backupPriorities.prio4()),
            // dated indices
            fullQualifiedNameWithMatcher(backupPriorities.prio4()),
            fullQualifiedName(backupPriorities.prio5()),
            fullQualifiedName(backupPriorities.prio6())
          };
    }
    return indexPatternsOrdered;
  }

  private <A extends BackupPriority> String[] fullQualifiedName(final Collection<A> backups) {
    return backups.stream().map(this::getFullQualifiedName).toArray(String[]::new);
  }

  private <A extends BackupPriority> String[] fullQualifiedNameWithMatcher(
      final Collection<A> backups) {
    return backups.stream()
        .map(this::getFullQualifiedName)
        .flatMap(name -> Stream.of(name + "*", "-" + name))
        .toArray(String[]::new);
  }

  // TODO Keep in mind this bug: https://github.com/camunda/camunda/issues/20458
  private String getCurrentVersion() {
    return backupProps.version().toLowerCase();
  }

  private String getFullQualifiedName(final BackupPriority index) {
    if (index != null) {
      return index.getFullQualifiedName();
    } else {
      throw new BackupException("Can't find out index name for backup.");
    }
  }
}
