import { setupFieldValuesEndpoint } from "__support__/server-mocks";
import { createMockEntitiesState } from "__support__/store";
import { renderWithProviders, screen } from "__support__/ui";
import { getMetadata } from "metabase/selectors/metadata";
import {
  PEOPLE,
  PRODUCTS,
  PRODUCT_CATEGORY_VALUES,
  createSampleDatabase,
} from "metabase-types/api/mocks/presets";
import { createMockState } from "metabase-types/store/mocks";

import { GlobalFingerprint } from "./GlobalFingerprint";

const state = createMockState({
  entities: createMockEntitiesState({
    databases: [createSampleDatabase()],
  }),
});

function setup({ field }) {
  setupFieldValuesEndpoint(PRODUCT_CATEGORY_VALUES);

  renderWithProviders(<GlobalFingerprint fieldId={field.id} />, {
    storeInitialState: state,
  });
}

describe("GlobalFingerprint", () => {
  const metadata = getMetadata(state);

  describe("when the field does not have a `has_field_values` value of 'list'", () => {
    const field = metadata.field(PEOPLE.ADDRESS);

    it("should not fetch field values when field values are empty", () => {
      setup({ field });
      expect(
        screen.queryByText("Getting distinct values..."),
      ).not.toBeInTheDocument();
      expect(screen.getByText(/distinct values/)).toBeInTheDocument();
    });
  });

  describe("when the field has a `has_field_values` value of 'list'", () => {
    const field = metadata.field(PRODUCTS.CATEGORY);

    it("should fetch field values when field values are empty", async () => {
      setup({ field });

      expect(
        screen.getByText("Getting distinct values..."),
      ).toBeInTheDocument();
      expect(await screen.findByText("4 distinct values")).toBeInTheDocument();
    });
  });

  it("should not throw an error when the field cannot be found", () => {
    setup({ field: { id: 99942 } });
    expect(
      screen.queryByText("Getting distinct values..."),
    ).not.toBeInTheDocument();
  });
});
