/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */

import {Selector} from 'testcafe';

export const multiSelect = Selector('.CarbonModal .MultiSelect');
export const typeaheadInput = Selector('.CarbonModal .Typeahead .Input');
export const stateFilterMultiSelect = Selector('.CarbonModal .MultiSelect');
export const stateFilterMultiSelectOption = (text) =>
  stateFilterMultiSelect.find('.DropdownOption').withText(text);
export const multiSelectOptionNumber = (idx) => multiSelect.find('.DropdownOption').nth(idx);
export const variableFilterOperatorButton = (text) =>
  Selector('.CarbonModal .buttonRow .Button').withText(text);
export const variableTypeahead = Selector('.variableContainer:last-of-type .Typeahead .Input');
export const variableFilterValueInput = Selector('.CarbonModal .ValueListInput input').nth(0);
export const variableOrButton = Selector('.MultipleVariableFilterModal .orButton');
export const removeVariableBtn = Selector('.MultipleVariableFilterModal .removeButton');
export const variableHeader = (text) => Selector('.variableContainer .sectionTitle').withText(text);
export const dateFilterTypeSelect = Selector('.DateRangeInput .Dropdown');
export const dateFilterTypeOption = (text) =>
  Selector('.DateRangeInput .DropdownOption').withText(text);
export const dateFilterStartInput = Selector('.DateFields .PickerDateInput:first-child input');
export const dateFilterEndInput = Selector('.DateFields .PickerDateInput:last-child input');
export const pickerDate = (number) =>
  Selector('.DateFields .rdrMonths .rdrMonth:first-child .rdrDay').withText(number);
export const infoText = Selector('.CarbonModal .tip');
export const dateTypeSelect = Selector('.selectGroup > .Select');
export const unitSelect = Selector('.unitSelection .Select');
export const customDateInput = Selector('.unitSelection').find('input');
export const durationFilterOperator = Selector('.DurationFilter .Select');
export const durationFilterInput = Selector('.DurationFilter input[type="text"]');
export const modalCancel = Selector('.CarbonModal .cancel');
export const stringValues = Selector('.CarbonModal .Checklist .itemsList');
export const firstMultiSelectValue = Selector(
  '.CarbonModal .Checklist .itemsList .LabeledInput .label'
);
export const multiSelectValue = (text) => firstMultiSelectValue.withText(text);
export const customValueCheckbox = Selector('.CarbonModal .customValueCheckbox');
export const addValueButton = Selector('.CarbonModal .customValueButton');
export const customValueInput = Selector('.CarbonModal .customValueInput input');
export const addValueToListButton = Selector('.CarbonModal .customValueInput button');
export const removeButtonFor = (text) =>
  Selector('.CarbonModal .Tag').withText(text).find('.close.Button');
export const editButton = Selector('.ActionItem .buttons button').nth(0);
