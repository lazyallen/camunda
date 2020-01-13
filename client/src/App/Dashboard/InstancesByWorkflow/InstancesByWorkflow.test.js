/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import React from 'react';
import {shallow} from 'enzyme';
import InstancesByWorkflow from './InstancesByWorkflow';
import InstancesBar from 'modules/components/InstancesBar';
import Collapse from '../Collapse';
import * as Styled from './styled';
import {createWorkflow, createInstanceByWorkflow} from 'modules/testUtils';

const mockInstancesByWorkflow = [
  createInstanceByWorkflow({
    workflows: [createWorkflow()]
  }),
  createInstanceByWorkflow({
    instancesWithActiveIncidentsCount: 65,
    activeInstancesCount: 136,
    workflowName: 'Order process',
    bpmnProcessId: 'orderProcess',
    workflows: [
      createWorkflow({name: 'First Version', version: 1}),
      createWorkflow({name: 'Second Version', version: 2})
    ]
  }),
  createInstanceByWorkflow({
    bpmnProcessId: 'noIncidentsProcess',
    workflowName: 'Without Incidents Process',
    instancesWithActiveIncidentsCount: 0,
    activeInstancesCount: 23,
    workflows: [createWorkflow()]
  }),
  createInstanceByWorkflow({
    instancesWithActiveIncidentsCount: 0,
    activeInstancesCount: 0,
    bpmnProcessId: 'noInstancesProcess',
    workflowName: 'Without Instances Process',
    workflows: [createWorkflow()]
  })
];

describe('InstancesByWorkflow', () => {
  it('should render a list', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );

    expect(node.type()).toBe('ul');
  });

  it('should render an li for each incident statistic', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );

    expect(node.find(Styled.Li).length).toBe(mockInstancesByWorkflow.length);
  });

  it('should pass the right data to InstancesBar', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );
    const nodeInstancesBar = node.find(InstancesBar).at(0);
    const statisticsAnchor = nodeInstancesBar.parent();

    expect(statisticsAnchor.props().to).toBe(
      '/instances?filter={"workflow":"loanProcess","version":"1","incidents":true,"active":true}&name="loanProcess"'
    );
    expect(statisticsAnchor.props().title).toBe(
      'View 138 Instances in 1 Version of Workflow loanProcess'
    );

    expect(nodeInstancesBar.props().label).toContain(
      mockInstancesByWorkflow[0].workflowName ||
        mockInstancesByWorkflow[0].bpmnProcessId
    );
    expect(nodeInstancesBar.props().label).toContain(
      mockInstancesByWorkflow[0].workflows[0].version
    );
    expect(nodeInstancesBar.props().incidentsCount).toBe(
      mockInstancesByWorkflow[0].instancesWithActiveIncidentsCount
    );
    expect(nodeInstancesBar.props().activeCount).toBe(
      mockInstancesByWorkflow[0].activeInstancesCount
    );

    expect(nodeInstancesBar).toMatchSnapshot();
  });

  it('should render a statistics/collapsable statistics based on number of versions', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );
    const firstStatistic = node.find('[data-test="incident-byWorkflow-0"]');
    const secondStatistic = node.find('[data-test="incident-byWorkflow-1"]');
    const thirdStatistic = node.find('[data-test="incident-byWorkflow-2"]');
    expect(firstStatistic.find(InstancesBar).length).toBe(1);
    expect(secondStatistic.find(Collapse).length).toBe(1);
    expect(thirdStatistic.find(InstancesBar).length).toBe(1);
  });

  it('passes the right data to the statistics collapse', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );
    const secondStatistic = node.find('[data-test="incident-byWorkflow-1"]');
    const collapseNode = secondStatistic.find(Collapse);
    const headerCollapseNode = collapseNode.props().header;
    const contentCollapseNode = collapseNode.props().content;
    const headerNode = shallow(headerCollapseNode);
    const headerStatistic = headerNode.find(InstancesBar);
    const contentNode = shallow(contentCollapseNode);

    // collapse button node
    expect(collapseNode.props().buttonTitle).toBe(
      'Expand 201 Instances of Workflow Order process'
    );

    // header anchor
    expect(headerNode.props().to).toBe(
      '/instances?filter={"workflow":"orderProcess","version":"all","incidents":true,"active":true}&name="Order process"'
    );
    expect(headerNode.props().title).toBe(
      'View 201 Instances in 2 Versions of Workflow Order process'
    );

    expect(headerStatistic.props().label).toContain(
      mockInstancesByWorkflow[1].workflowName ||
        mockInstancesByWorkflow[1].bpmnProcessId
    );
    expect(headerStatistic.props().label).toContain(
      mockInstancesByWorkflow[1].workflows[0].version
    );
    expect(headerStatistic.props().label).toContain(
      mockInstancesByWorkflow[1].workflows[1].version
    );
    expect(headerStatistic.props().incidentsCount).toBe(
      mockInstancesByWorkflow[1].instancesWithActiveIncidentsCount
    );
    expect(headerStatistic.props().activeCount).toBe(
      mockInstancesByWorkflow[1].activeInstancesCount
    );
    // should render a list with 2 items
    expect(contentNode.find(Styled.VersionLi).length).toBe(2);

    // should render two statistics
    expect(contentNode.find(InstancesBar).length).toBe(2);
    // should pass the right props to a statistic

    const versionStatisticNode = contentNode.find(InstancesBar).at(0);

    expect(versionStatisticNode.props().label).toContain(
      `Version ${mockInstancesByWorkflow[1].workflows[0].version}`
    );
    expect(versionStatisticNode.props().label).toContain(
      `${mockInstancesByWorkflow[1].workflows[0].name}`
    );
    expect(versionStatisticNode.props().size).toBe('small');
    expect(versionStatisticNode.props().incidentsCount).toBe(
      mockInstancesByWorkflow[1].workflows[0].instancesWithActiveIncidentsCount
    );
    expect(versionStatisticNode.props().activeCount).toBe(
      mockInstancesByWorkflow[1].workflows[0].activeInstancesCount
    );
  });

  it('should pass the right data to workflow without incidents', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );

    const workflowNode = node
      .find('[data-test="incident-byWorkflow-2"]')
      .dive();

    const nodeInstancesBar = workflowNode.find(InstancesBar);
    const statisticsAnchor = nodeInstancesBar.parent();

    expect(statisticsAnchor.props().to).toBe(
      '/instances?filter={"workflow":"noIncidentsProcess","version":"1","incidents":true,"active":true}&name="Without Incidents Process"'
    );
    expect(statisticsAnchor.props().title).toBe(
      'View 23 Instances in 1 Version of Workflow Without Incidents Process'
    );

    expect(nodeInstancesBar.props().label).toContain(
      mockInstancesByWorkflow[2].workflowName ||
        mockInstancesByWorkflow[2].bpmnProcessId
    );
    expect(nodeInstancesBar.props().label).toContain(
      mockInstancesByWorkflow[2].workflows[0].version
    );
    expect(nodeInstancesBar.props().incidentsCount).toBe(
      mockInstancesByWorkflow[2].instancesWithActiveIncidentsCount
    );
    expect(nodeInstancesBar.props().activeCount).toBe(
      mockInstancesByWorkflow[2].activeInstancesCount
    );
  });

  it('should pass the right data to workflow without instances', () => {
    const node = shallow(
      <InstancesByWorkflow incidents={mockInstancesByWorkflow} />
    );

    const workflowNode = node
      .find('[data-test="incident-byWorkflow-3"]')
      .dive();

    const nodeInstancesBar = workflowNode.find(InstancesBar);
    const statisticsAnchor = nodeInstancesBar.parent();

    expect(statisticsAnchor.props().to).toBe(
      '/instances?filter={"workflow":"noInstancesProcess","version":"1","incidents":true,"active":true,"completed":true,"canceled":true}&name="Without Instances Process"'
    );
    expect(statisticsAnchor.props().title).toBe(
      'View 0 Instances in 1 Version of Workflow Without Instances Process'
    );

    expect(nodeInstancesBar.props().label).toContain(
      mockInstancesByWorkflow[3].workflowName ||
        mockInstancesByWorkflow[3].bpmnProcessId
    );
    expect(nodeInstancesBar.props().label).toContain(
      mockInstancesByWorkflow[3].workflows[0].version
    );
    expect(nodeInstancesBar.props().incidentsCount).toBe(
      mockInstancesByWorkflow[3].instancesWithActiveIncidentsCount
    );
    expect(nodeInstancesBar.props().activeCount).toBe(
      mockInstancesByWorkflow[3].activeInstancesCount
    );
  });
});
