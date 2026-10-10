/** Notification subscriptions behavior (S12-01, AC4/AC5): the routing
 * table listed with channel/recipients/enabled chips, the create POST
 * carrying the frozen event names and the channel-appropriate
 * recipients, the webhook secret write-only (create carries it, the
 * served view never does), and the delivery log rendering status,
 * attempts, and the last error of FAILED rows. */
import { cleanup, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { Route, Routes } from "react-router-dom";
import NotificationsPage from "./NotificationsPage";
import { installFetchRouter, renderConsole, seedSession } from "../test/harness";

const user = userEvent.setup();

const ORG_ID = "10000000-0000-4000-8000-000000000001";

const SUBSCRIPTION_IN_APP = {
  id: "40000000-0000-4000-8000-000000000001",
  organizationId: ORG_ID,
  eventTypes: ["FEEDBACK_MARKED_CRITICAL", "RELEASE_PROMOTED"],
  channel: "IN_APP",
  recipientRoles: ["QA_ENGINEER"],
  recipientUserIds: [],
  webhookUrl: null,
  enabled: true,
  createdAt: "2026-10-04T10:00:00Z",
  updatedAt: "2026-10-04T10:00:00Z",
};

const SUBSCRIPTION_FAILED_WEBHOOK = {
  id: "40000000-0000-4000-8000-000000000002",
  organizationId: ORG_ID,
  eventTypes: ["RELEASE_PROMOTED"],
  channel: "WEBHOOK",
  recipientRoles: [],
  recipientUserIds: [],
  webhookUrl: "https://hooks.example.test/shipperdog",
  enabled: false,
  createdAt: "2026-10-03T10:00:00Z",
  updatedAt: "2026-10-04T11:00:00Z",
};

const DELIVERY_FAILED = {
  id: "50000000-0000-4000-8000-000000000001",
  eventId: "60000000-0000-4000-8000-000000000001",
  eventName: "RELEASE_PROMOTED",
  subscriptionId: SUBSCRIPTION_FAILED_WEBHOOK.id,
  channel: "WEBHOOK",
  recipientKey: "webhook:https://hooks.example.test/shipperdog",
  status: "FAILED",
  attempts: 5,
  lastError: "HttpClientException: 503 Service Unavailable",
  deliveredAt: null,
  createdAt: "2026-10-04T11:01:00Z",
};

const DELIVERY_DELIVERED = {
  id: "50000000-0000-4000-8000-000000000002",
  eventId: "60000000-0000-4000-8000-000000000002",
  eventName: "FEEDBACK_MARKED_CRITICAL",
  subscriptionId: SUBSCRIPTION_IN_APP.id,
  channel: "IN_APP",
  recipientKey: `user:70000000-0000-4000-8000-000000000001`,
  status: "DELIVERED",
  attempts: 1,
  lastError: null,
  deliveredAt: "2026-10-04T10:00:05Z",
  createdAt: "2026-10-04T10:00:05Z",
};

function fetchCalls(): Array<[string, RequestInit | undefined]> {
  const mock = fetch as unknown as { mock: { calls: Array<[string, RequestInit]> } };
  return mock.mock.calls;
}

function bodyOf(url: string, method: string): Record<string, unknown> {
  const call = fetchCalls().find(
    ([candidate, init]) => candidate === url && (init?.method ?? "GET") === method,
  );
  expect(call, `${method} ${url} was issued`).toBeDefined();
  return JSON.parse((call![1] as RequestInit).body as string) as Record<string, unknown>;
}

function renderPage(extra: Parameters<typeof installFetchRouter>[0] = []): Parameters<
  typeof installFetchRouter
>[0] {
  const routes: Parameters<typeof installFetchRouter>[0] = [
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/notification-subscriptions`,
      status: 200,
      body: { items: [SUBSCRIPTION_IN_APP, SUBSCRIPTION_FAILED_WEBHOOK] },
    },
    {
      method: "GET",
      url: `/api/v1/organizations/${ORG_ID}/notification-deliveries`,
      status: 200,
      body: { items: [DELIVERY_FAILED, DELIVERY_DELIVERED] },
    },
    ...extra,
  ];
  installFetchRouter(routes);
  seedSession();
  renderConsole(
    <Routes>
      <Route path="/notifications" element={<NotificationsPage />} />
    </Routes>,
    { route: "/notifications" },
  );
  return routes;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("NotificationsPage", () => {
  it("lists the routing table with channel, recipients, and standing", async () => {
    renderPage();

    const list = await screen.findByTestId("subscription-list");
    expect(list.children.length).toBe(2);
    expect(screen.getByTestId(`subscription-${SUBSCRIPTION_IN_APP.id}`)).toHaveTextContent("in-app");
    expect(screen.getByTestId(`subscription-${SUBSCRIPTION_IN_APP.id}`)).toHaveTextContent(
      "FEEDBACK_MARKED_CRITICAL, RELEASE_PROMOTED",
    );
    expect(screen.getByTestId(`subscription-${SUBSCRIPTION_IN_APP.id}`)).toHaveTextContent("qa engineer");
    expect(screen.getByTestId(`subscription-${SUBSCRIPTION_FAILED_WEBHOOK.id}`)).toHaveTextContent("parked");
    expect(screen.getByTestId(`subscription-${SUBSCRIPTION_FAILED_WEBHOOK.id}`)).toHaveTextContent(
      "https://hooks.example.test/shipperdog",
    );
  });

  it("renders the delivery log with status, attempts, and the last error", async () => {
    renderPage();

    const deliveries = await screen.findByTestId("delivery-list");
    expect(deliveries.children.length).toBe(2);
    expect(screen.getByTestId(`delivery-${DELIVERY_FAILED.id}`)).toHaveTextContent("FAILED");
    expect(screen.getByTestId(`delivery-${DELIVERY_FAILED.id}`)).toHaveTextContent("5 attempts");
    expect(screen.getByTestId(`delivery-${DELIVERY_FAILED.id}`)).toHaveTextContent("503");
    expect(screen.getByTestId(`delivery-${DELIVERY_DELIVERED.id}`)).toHaveTextContent("DELIVERED");
  });

  it("creates a webhook subscription carrying the frozen names and the secret", async () => {
    const routes = renderPage();
    routes.push({
      method: "POST",
      url: `/api/v1/organizations/${ORG_ID}/notification-subscriptions`,
      status: 201,
      body: SUBSCRIPTION_FAILED_WEBHOOK,
    });

    await screen.findByTestId("subscription-list");
    await user.click(screen.getByTestId("subscription-create"));
    await user.click(within(screen.getByTestId("subscription-channel")).getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "Webhook (signed)" }));
    await user.type(
      screen.getByTestId("subscription-webhook-url").querySelector("input")!,
      "https://hooks.example.test/new",
    );
    await user.type(
      screen.getByTestId("subscription-webhook-secret").querySelector("input")!,
      "a-signing-secret-16-chars",
    );
    await user.click(screen.getByTestId("subscription-save"));

    await waitFor(() => {
      const body = bodyOf(`/api/v1/organizations/${ORG_ID}/notification-subscriptions`, "POST");
      expect(body["eventTypes"]).toEqual(["RELEASE_PROMOTED"]);
      expect(body["channel"]).toBe("WEBHOOK");
      expect(body["webhookUrl"]).toBe("https://hooks.example.test/new");
      expect(body["webhookSecret"]).toBe("a-signing-secret-16-chars");
    });
  });

  it("parks a subscription through the edit dialog", async () => {
    const routes = renderPage();
    routes.push({
      method: "PATCH",
      url: `/api/v1/organizations/${ORG_ID}/notification-subscriptions/${SUBSCRIPTION_IN_APP.id}`,
      status: 200,
      body: { ...SUBSCRIPTION_IN_APP, enabled: false },
    });

    await screen.findByTestId("subscription-list");
    await user.click(screen.getByTestId(`subscription-edit-${SUBSCRIPTION_IN_APP.id}`));
    await user.click(screen.getByTestId("subscription-enabled"));
    await user.click(screen.getByTestId("subscription-save"));

    await waitFor(() => {
      const body = bodyOf(
        `/api/v1/organizations/${ORG_ID}/notification-subscriptions/${SUBSCRIPTION_IN_APP.id}`,
        "PATCH",
      );
      expect(body["enabled"]).toBe(false);
    });
  });

  it("creates a Slack subscription with the webhook family's URL and secret shape (S12-02)", async () => {
    const routes = renderPage();
    const slackSubscription = {
      ...SUBSCRIPTION_FAILED_WEBHOOK,
      id: "40000000-0000-4000-8000-000000000003",
      channel: "SLACK",
      enabled: true,
      webhookUrl: "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXXXXXXXXXX",
    };
    routes.push({
      method: "POST",
      url: `/api/v1/organizations/${ORG_ID}/notification-subscriptions`,
      status: 201,
      body: slackSubscription,
    });

    await screen.findByTestId("subscription-list");
    await user.click(screen.getByTestId("subscription-create"));
    await user.click(within(screen.getByTestId("subscription-channel")).getByRole("combobox"));
    await user.click(screen.getByRole("option", { name: "Slack" }));
    await user.type(
      screen.getByTestId("subscription-webhook-url").querySelector("input")!,
      "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXXXXXXXXXX",
    );
    await user.type(
      screen.getByTestId("subscription-webhook-secret").querySelector("input")!,
      "a-signing-secret-16-chars",
    );
    await user.click(screen.getByTestId("subscription-save"));

    await waitFor(() => {
      const body = bodyOf(`/api/v1/organizations/${ORG_ID}/notification-subscriptions`, "POST");
      expect(body["channel"]).toBe("SLACK");
      expect(body["webhookUrl"]).toBe("https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXXXXXXXXXX");
      expect(body["webhookSecret"]).toBe("a-signing-secret-16-chars");
      expect(body["recipientRoles"]).toBeUndefined();
    });
  });

  it("sends the test message and renders the failed outcome inline (S12-02, AC3)", async () => {
    renderPage([
      {
        method: "POST",
        url: `/api/v1/organizations/${ORG_ID}/notification-subscriptions/${SUBSCRIPTION_FAILED_WEBHOOK.id}/test`,
        status: 200,
        body: {
          delivered: false,
          channel: "TEAMS",
          error: "HttpClientException: 503 Service Unavailable",
          sentAt: "2026-10-04T12:00:00Z",
        },
      },
      // The failed test's ledger row shows after the deliveries refresh.
      {
        method: "GET",
        url: `/api/v1/organizations/${ORG_ID}/notification-deliveries`,
        status: 200,
        body: { items: [DELIVERY_FAILED, DELIVERY_DELIVERED] },
      },
    ]);

    await screen.findByTestId("subscription-list");
    await user.click(screen.getByTestId(`subscription-test-${SUBSCRIPTION_FAILED_WEBHOOK.id}`));

    const result = await screen.findByTestId("test-result");
    expect(result).toHaveTextContent("teams");
    expect(result).toHaveTextContent("503");
    await waitFor(() => {
      expect(
        fetchCalls().some(
          ([url, init]) =>
            url ===
              `/api/v1/organizations/${ORG_ID}/notification-subscriptions/${SUBSCRIPTION_FAILED_WEBHOOK.id}/test` &&
            (init?.method ?? "GET") === "POST",
        ),
      ).toBe(true);
    });
  });
});
