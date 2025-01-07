/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {expect} from '@playwright/test';
import {test} from '../test-fixtures';
import {
  mockBatchOperations,
  mockGroupedProcesses,
  mockProcessInstances,
  mockStatistics,
  mockResponses,
} from '../mocks/processes.mocks';
import {open} from 'modules/mocks/diagrams';

test.beforeEach(async ({context}) => {
  await context.route('**/client-config.js', (route) =>
    route.fulfill({
      status: 200,
      headers: {
        'Content-Type': 'text/javascript;charset=UTF-8',
      },
      body: `window.clientConfig = ${JSON.stringify({
        isEnterprise: false,
        canLogout: true,
        contextPath: '',
        baseName: '/operate',
        organizationId: null,
        clusterId: null,
        mixpanelAPIHost: null,
        mixpanelToken: null,
        isLoginDelegated: false,
        tasklistUrl: null,
        resourcePermissionsEnabled: false,
        multiTenancyEnabled: false,
      })};`,
    }),
  );
});

test.describe('migration view', () => {
  for (const theme of ['light', 'dark']) {
    test(`initial migration view - ${theme}`, async ({
      page,
      commonPage,
      processesPage,
    }) => {
      await commonPage.changeTheme(theme);

      await page.addInitScript(() => {
        window.localStorage.setItem(
          'panelStates',
          JSON.stringify({
            isOperationsCollapsed: true,
          }),
        );
      }, theme);

      await page.route(
        /^.*\/api.*$/i,
        mockResponses({
          groupedProcesses: mockGroupedProcesses,
          batchOperations: mockBatchOperations,
          processInstances: mockProcessInstances,
          statistics: mockStatistics,
          processXml: open('LotsOfTasks.bpmn'),
        }),
      );

      await processesPage.navigateToProcesses({
        searchParams: {
          active: 'true',
          incidents: 'true',
          process: 'LotsOfTasks',
          version: '1',
        },
        options: {
          waitUntil: 'networkidle',
        },
      });

      await processesPage.getNthProcessInstanceCheckbox(0).click();
      await processesPage.migrateButton.click();
      await processesPage.migrationModal.confirmButton.click();

      await expect(page).toHaveScreenshot();
    });
  }
});
