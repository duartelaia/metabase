import { type ReactNode, useState } from "react";

import type {
  DatePickerUnit,
  RelativeDatePickerValue,
} from "metabase/querying/filters/types";
import { Box, Divider, Flex, PopoverBackButton, Tabs } from "metabase/ui";

import type { DatePickerSubmitButtonProps } from "../types";
import { renderDefaultSubmitButton } from "../utils";

import { CurrentDatePicker } from "./CurrentDatePicker";
import { DateIntervalPicker } from "./DateIntervalPicker";
import { DateOffsetIntervalPicker } from "./DateOffsetIntervalPicker";
import S from "./RelativeDatePicker.module.css";
import { DEFAULT_VALUE, TABS } from "./constants";
import {
  getDirection,
  isIntervalValue,
  isOffsetIntervalValue,
  setDirection,
} from "./utils";

interface RelativeDatePickerProps {
  value: RelativeDatePickerValue | undefined;
  availableUnits: DatePickerUnit[];
  renderSubmitButton?: (props: DatePickerSubmitButtonProps) => ReactNode;
  onChange: (value: RelativeDatePickerValue) => void;
  onBack: () => void;
}

export function RelativeDatePicker({
  value: initialValue,
  availableUnits,
  renderSubmitButton = renderDefaultSubmitButton,
  onChange,
  onBack,
}: RelativeDatePickerProps) {
  const [value, setValue] = useState<RelativeDatePickerValue | undefined>(
    initialValue ?? DEFAULT_VALUE,
  );
  const direction = getDirection(value);

  const handleTabChange = (tabValue: string | null) => {
    const tab = TABS.find((tab) => tab.direction === tabValue);
    if (tab) {
      setValue(setDirection(value, tab.direction));
    }
  };

  const handleSubmit = () => {
    if (value != null) {
      onChange(value);
    }
  };

  return (
    <Tabs value={direction} onChange={handleTabChange}>
      <Flex>
        <PopoverBackButton p="sm" onClick={onBack} />
        <Tabs.List className={S.TabList}>
          {TABS.map((tab) => (
            <Tabs.Tab key={tab.direction} value={tab.direction}>
              {tab.label}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Flex>
      <Divider />
      {TABS.map((tab) => (
        <Tabs.Panel key={tab.direction} value={tab.direction}>
          {value != null && isOffsetIntervalValue(value) ? (
            <DateOffsetIntervalPicker
              value={value}
              availableUnits={availableUnits}
              renderSubmitButton={renderSubmitButton}
              onChange={setValue}
              onSubmit={handleSubmit}
            />
          ) : value != null && isIntervalValue(value) ? (
            <DateIntervalPicker
              value={value}
              availableUnits={availableUnits}
              renderSubmitButton={renderSubmitButton}
              onChange={setValue}
              onSubmit={handleSubmit}
            />
          ) : (
            <Box p="md">
              <CurrentDatePicker
                value={value}
                availableUnits={availableUnits}
                onChange={onChange}
              />
            </Box>
          )}
        </Tabs.Panel>
      ))}
    </Tabs>
  );
}
