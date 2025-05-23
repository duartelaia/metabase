import userEvent from "@testing-library/user-event";

import { renderWithProviders, screen, within } from "__support__/ui";
import type { Parameter, ParameterValue } from "metabase-types/api";
import { createMockParameter } from "metabase-types/api/mocks";

import {
  NumberInputWidget,
  type NumberInputWidgetProps,
} from "./NumberInputWidget";

type SetupOpts = Omit<NumberInputWidgetProps, "setValue"> & {
  parameter?: Parameter;
};

const setup = ({ parameter = createMockParameter(), ...props }: SetupOpts) => {
  const setValue = jest.fn();

  renderWithProviders(
    <NumberInputWidget {...props} setValue={setValue} parameter={parameter} />,
  );

  return {
    setValue,
  };
};

describe("NumberInputWidget", () => {
  beforeEach(() => {
    jest.resetAllMocks();
  });

  describe("arity of 1", () => {
    it("should render an input populated with a value", () => {
      setup({ value: [123] });

      const textbox = screen.getByRole("textbox");
      expect(textbox).toBeInTheDocument();
      expect(textbox).toHaveValue("123");
    });

    it("should render an empty input", () => {
      setup({ value: undefined });

      const textbox = screen.getByRole("textbox");
      expect(textbox).toBeInTheDocument();
      expect(textbox).toHaveAttribute("placeholder", "Enter a number");
    });

    it("should render a disabled update button, until the value is changed", async () => {
      setup({ value: [123] });

      const button = screen.getByRole("button", { name: "Update filter" });
      expect(button).toBeInTheDocument();
      expect(button).toBeDisabled();

      await userEvent.type(screen.getByRole("textbox"), "456");
      expect(button).toBeEnabled();
    });

    it("should allow to update the input with a new value", async () => {
      const { setValue } = setup({ value: [123] });

      const textbox = screen.getByRole("textbox");
      await userEvent.type(textbox, "{backspace}{backspace}{backspace}");
      await userEvent.type(textbox, "456");
      const button = screen.getByRole("button", { name: "Update filter" });
      await userEvent.click(button);
      expect(setValue).toHaveBeenCalledWith([456]);
    });

    it("should allow to update the input with an undefined value", async () => {
      const { setValue } = setup({ value: [1] });

      const textbox = screen.getByRole("textbox");
      const button = screen.getByRole("button", { name: "Update filter" });
      await userEvent.type(textbox, "{backspace}");
      await userEvent.click(button);
      expect(setValue).toHaveBeenCalledWith(undefined);
    });

    it("should allow to submit a value on enter", async () => {
      const { setValue } = setup({ value: [] });
      await userEvent.type(screen.getByRole("textbox"), "10{enter}");
      expect(setValue).toHaveBeenCalledWith([10]);
    });

    it("should allow to submit an empty value on enter if the parameter is not required", async () => {
      const { setValue } = setup({ value: ["10"] });
      const input = screen.getByRole("textbox");
      await userEvent.clear(input);
      await userEvent.type(input, "{enter}");
      expect(setValue).toHaveBeenCalledWith(undefined);
    });

    it("should not allow to submit an empty value on enter if the parameter is required", async () => {
      const { setValue } = setup({
        value: ["10"],
        parameter: createMockParameter({ required: true }),
      });
      const input = screen.getByRole("textbox");
      await userEvent.clear(input);
      await userEvent.type(input, "{enter}");
      expect(setValue).not.toHaveBeenLastCalledWith(undefined);
    });
  });

  describe("arity of 2", () => {
    it("should render an input populated with a value", () => {
      setup({ value: [123, 456], arity: 2 });

      const [textbox1, textbox2] = screen.getAllByRole("textbox");
      expect(textbox1).toBeInTheDocument();
      expect(textbox1).toHaveValue("123");

      expect(textbox2).toBeInTheDocument();
      expect(textbox2).toHaveValue("456");
    });

    it("should be invalid when one of the inputs is empty", async () => {
      setup({ value: [123, 456], arity: 2 });

      const [textbox1] = screen.getAllByRole("textbox");
      await userEvent.clear(textbox1);
      const button = screen.getByRole("button", { name: "Update filter" });
      expect(button).toBeDisabled();
    });

    it("should be settable", async () => {
      const { setValue } = setup({ value: undefined, arity: 2 });

      const [textbox1, textbox2] = screen.getAllByRole("textbox");
      await userEvent.type(textbox1, "1");
      await userEvent.type(textbox2, "2");

      const button = screen.getByRole("button", { name: "Add filter" });
      await userEvent.click(button);

      expect(setValue).toHaveBeenCalledWith([1, 2]);
    });

    it("should correctly parse big integers", async () => {
      const { setValue } = setup({ value: undefined, arity: 2 });

      const [textbox1, textbox2] = screen.getAllByRole("textbox");
      await userEvent.type(textbox1, "9007199254740993");
      await userEvent.type(textbox2, "9007199254740994");

      const button = screen.getByRole("button", { name: "Add filter" });
      await userEvent.click(button);

      expect(setValue).toHaveBeenCalledWith([
        "9007199254740993",
        "9007199254740994",
      ]);
    });

    it("should be clearable by emptying all inputs", async () => {
      const { setValue } = setup({ value: [123, 456], arity: 2 });

      const [textbox1, textbox2] = screen.getAllByRole("textbox");
      await userEvent.clear(textbox1);
      await userEvent.clear(textbox2);

      const button = screen.getByRole("button", { name: "Update filter" });
      await userEvent.click(button);

      expect(setValue).toHaveBeenCalledWith(undefined);
    });
  });

  describe("arity of n", () => {
    it("should render a multi autocomplete input", () => {
      const value = [1, 2, 3, 4];
      setup({ value, arity: "n" });

      const valueList = screen.getByRole("list");

      for (const item of value) {
        const value = getValue(valueList, item);
        expect(value).toBeInTheDocument();
      }
    });

    it("should correctly parse number inputs", async () => {
      const { setValue } = setup({ value: undefined, arity: "n" });

      const input = screen.getByRole("combobox");
      const valueList = screen.getByRole("list");
      await userEvent.type(input, "foo,123abc,456,", {
        pointerEventsCheck: 0,
      });

      expect(getValue(valueList, 456)).toBeInTheDocument();

      const button = screen.getByRole("button", { name: "Add filter" });
      await userEvent.click(button);
      expect(setValue).toHaveBeenCalledWith([123, 456]);
    });

    it("should correctly parse big integers", async () => {
      const { setValue } = setup({ value: undefined, arity: "n" });

      const input = screen.getByRole("combobox");
      const valueList = screen.getByRole("list");
      await userEvent.type(input, "9007199254740993,", {
        pointerEventsCheck: 0,
      });
      expect(getValue(valueList, "9007199254740993")).toBeInTheDocument();

      const button = screen.getByRole("button", { name: "Add filter" });
      await userEvent.click(button);
      expect(setValue).toHaveBeenCalledWith(["9007199254740993"]);
    });

    it("should be unsettable", async () => {
      const { setValue } = setup({ value: [1, 2], arity: "n" });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "{backspace}{backspace}", {
        pointerEventsCheck: 0,
      });

      const button = screen.getByRole("button", { name: "Update filter" });

      await userEvent.click(button);
      expect(setValue).toHaveBeenCalledWith(undefined);
    });

    it("should render the correct label if the parameter has custom labels configured", async () => {
      const values: ParameterValue[] = [["42", "Foo"], ["66", "Bar"], ["55"]];
      const parameter = createMockParameter({
        values_source_type: "static-list",
        values_source_config: { values },
      });

      const { setValue } = setup({
        value: [42, 55],
        arity: "n",
        parameter,
      });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "Ba", {
        pointerEventsCheck: 0,
      });

      await userEvent.click(screen.getByText("Bar"));

      const button = screen.getByRole("button", { name: "Update filter" });
      await userEvent.click(button);

      expect(setValue).toHaveBeenCalledWith([42, 55, 66]);
    });

    it("allow entering comma-separated value by label", async () => {
      const values: ParameterValue[] = [["42", "Foo"], ["66", "Bar"], ["55"]];
      const parameter = createMockParameter({
        values_source_type: "static-list",
        values_source_config: { values },
      });

      const { setValue } = setup({
        value: [],
        arity: "n",
        parameter,
      });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "Foo,Bar,55,", {
        pointerEventsCheck: 0,
      });

      const button = screen.getByRole("button", { name: "Add filter" });
      await userEvent.click(button);

      expect(setValue).toHaveBeenCalledWith([55]);
    });

    it("should allow to submit a value on enter", async () => {
      const { setValue } = setup({ value: [], arity: "n" });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "10{enter}");
      expect(screen.getByText("10")).toBeInTheDocument();
      expect(setValue).not.toHaveBeenCalled();

      await userEvent.type(input, "{enter}");
      expect(setValue).toHaveBeenCalledWith([10]);
    });

    it("should allow to submit multiple values on enter", async () => {
      const { setValue } = setup({ value: [], arity: "n" });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "10,20{enter}");
      expect(screen.getByText("10")).toBeInTheDocument();
      expect(screen.getByText("20")).toBeInTheDocument();
      expect(setValue).not.toHaveBeenCalled();

      await userEvent.type(input, "{enter}");
      expect(setValue).toHaveBeenCalledWith([10, 20]);
    });

    it("should allow to submit an empty value on enter if the parameter is not required", async () => {
      const { setValue } = setup({ value: [10], arity: "n" });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "{backspace}{enter}");
      expect(setValue).toHaveBeenCalledWith(undefined);
    });

    it("should not allow to submit an empty value on enter if the parameter is required", async () => {
      const { setValue } = setup({
        value: [10],
        parameter: createMockParameter({ required: true }),
        arity: "n",
      });

      const input = screen.getByRole("combobox");
      await userEvent.type(input, "{backspace}{enter}");
      expect(setValue).not.toHaveBeenLastCalledWith(undefined);
    });
  });
});

function getValue(parent: HTMLElement, value: number | string) {
  return within(parent).getByText(value.toString());
}
