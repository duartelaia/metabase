import { setupEnterprisePlugins } from "__support__/enterprise";
import { setupGroupsEndpoint } from "__support__/server-mocks";
import { screen } from "__support__/ui";
import {
  createMockGroup,
  createMockTokenFeatures,
} from "metabase-types/api/mocks";

import type { SetupOpts } from "./setup";
import { setup } from "./setup";

const setupPremium = async (feature: string, opts?: SetupOpts) => {
  setupEnterprisePlugins();
  setupGroupsEndpoint([createMockGroup()]);
  await setup({
    ...opts,
    tokenFeatures: createMockTokenFeatures({
      [feature]: true,
      sso_google: true,
    }),
    hasEnterprisePlugins: true,
  });
};

describe("SettingsEditorApp", () => {
  it("shows JWT auth option", async () => {
    await setupPremium("sso_jwt", {
      initialRoute: "/admin/settings/authentication",
    });

    expect(await screen.findByText("JWT")).toBeInTheDocument();
    expect(
      await screen.findByText(
        "Allows users to login via a JWT Identity Provider.",
      ),
    ).toBeInTheDocument();
  });

  it("shows SAML auth option", async () => {
    await setupPremium("sso_saml", {
      initialRoute: "/admin/settings/authentication",
    });

    expect(await screen.findByText("SAML")).toBeInTheDocument();
    expect(
      await screen.findByText(
        "Allows users to login via a SAML Identity Provider.",
      ),
    ).toBeInTheDocument();
  });

  it("lets users access JWT settings", async () => {
    await setupPremium("sso_jwt", {
      initialRoute: "/admin/settings/authentication/jwt",
    });

    expect(await screen.findByText("Server Settings")).toBeInTheDocument();
    expect(
      await screen.findByText(/JWT Identity Provider URI/),
    ).toBeInTheDocument();
  });

  it("lets users access SAML settings", async () => {
    await setupPremium("sso_saml", {
      initialRoute: "/admin/settings/authentication/saml",
    });

    expect(
      await screen.findByText("Set up SAML-based SSO"),
    ).toBeInTheDocument();
    expect(
      await screen.findByText("Configure your identity provider (IdP)"),
    ).toBeInTheDocument();
  });

  it("shows session timeout option", async () => {
    await setupPremium("session_timeout_config", {
      initialRoute: "/admin/settings/authentication",
    });
    expect(await screen.findByText("Session timeout")).toBeInTheDocument();
  });
});
