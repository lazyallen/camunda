/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import React from 'react';
import {shallow} from 'enzyme';

import Analysis from './Analysis';

it('should render sub navigation', () => {
  const node = shallow(<Analysis />);

  expect(node).toMatchSnapshot();
});
