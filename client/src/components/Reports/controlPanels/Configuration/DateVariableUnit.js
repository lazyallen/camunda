/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import React from 'react';

import {Select} from 'components';
import {t} from 'translation';

export default function DateVariableUnit({
  report: {
    data: {configuration, groupBy},
  },
  onChange,
}) {
  if (groupBy?.type === 'variable' && groupBy.value?.type === 'Date') {
    return (
      <fieldset className="DateVariableUnit">
        <legend>{t('report.config.bucket.buckets')}</legend>
        <Select
          value={configuration.groupByDateVariableUnit}
          onChange={(value) => onChange({groupByDateVariableUnit: {$set: value}}, true)}
        >
          <Select.Option value="automatic">{t('common.unit.automatic')}</Select.Option>
          <Select.Option value="hour">{t('common.unit.hour.label-plural')}</Select.Option>
          <Select.Option value="day">{t('common.unit.day.label-plural')}</Select.Option>
          <Select.Option value="week">{t('common.unit.week.label-plural')}</Select.Option>
          <Select.Option value="month">{t('common.unit.month.label-plural')}</Select.Option>
          <Select.Option value="year">{t('common.unit.year.label-plural')}</Select.Option>
        </Select>
      </fieldset>
    );
  }
  return null;
}
