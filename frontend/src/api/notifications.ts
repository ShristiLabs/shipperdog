/** Notification-surface client (S12-01 backend, docs/notifications.md):
 * the org-scoped subscription routing table (events x channels x
 * recipients, the CRUD an Organization Admin manages) and the delivery
 * ledger (every attempt, failures first-class). The webhook signing
 * secret is write-only: create and update accept it, no view returns
 * it. The inbox is self-scoped (the caller's own in-app rows). */
import { apiFetch, pageFetch } from "./client";

/** FR-023's V1.5 channels: the inbox write, the EmailSender seam, the
 * signed webhook POST. */
export type NotificationChannel = "IN_APP" | "EMAIL" | "WEBHOOK";

/** One delivery-ledger row: the attempt trail the log renders. */
export interface DeliveryRow {
  id: string;
  eventId: string;
  eventName: string;
  subscriptionId: string;
  channel: NotificationChannel;
  recipientKey: string;
  status: "SCHEDULED" | "DELIVERED" | "FAILED";
  attempts: number;
  lastError: string | null;
  deliveredAt: string | null;
  createdAt: string;
}

/** The subscription view the API serves (no webhookSecret member by
 * construction — the field is write-only material). */
export interface Subscription {
  id: string;
  organizationId: string;
  eventTypes: string[];
  channel: NotificationChannel;
  recipientRoles: string[];
  recipientUserIds: string[];
  webhookUrl: string | null;
  enabled: boolean;
  createdAt: string;
  updatedAt: string;
}

/** The create body: event names from the frozen vocabulary, a channel,
 * and the channel-appropriate recipients. */
export interface SubscriptionCreateInput {
  eventTypes: string[];
  channel: NotificationChannel;
  recipientRoles?: string[];
  recipientUserIds?: string[];
  webhookUrl?: string;
  webhookSecret?: string;
}

/** The edit body: absent fields keep their standing; a supplied
 * webhookSecret rotates it; the channel never moves. */
export interface SubscriptionUpdateInput {
  eventTypes?: string[];
  recipientRoles?: string[];
  recipientUserIds?: string[];
  webhookUrl?: string;
  webhookSecret?: string;
  enabled?: boolean;
}

const BASE = (organizationId: string) =>
  `/organizations/${encodeURIComponent(organizationId)}/notification-subscriptions`;

/** The organization's subscriptions, newest first, one capped walk. */
export async function fetchSubscriptions(organizationId: string): Promise<Subscription[]> {
  const subscriptions: Subscription[] = [];
  let cursor: string | undefined;
  let pages = 0;
  do {
    const page = await pageFetch<Subscription>(`/organizations/${organizationId}/notification-subscriptions`, {
      limit: 100,
      cursor,
    });
    subscriptions.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return subscriptions;
}

export function getSubscription(organizationId: string, subscriptionId: string): Promise<Subscription> {
  return apiFetch<Subscription>(`${BASE(organizationId)}/${subscriptionId}`);
}

/** The nav probe (the rings pattern): one-item listing — org:notifications:read
 * holders pass, everyone else is conceal-denied and the section hides. */
export function getSubscriptionProbe(organizationId: string): Promise<{ items: Subscription[] }> {
  return apiFetch<{ items: Subscription[] }>(`${BASE(organizationId)}?limit=1`);
}

export function createSubscription(
  organizationId: string,
  input: SubscriptionCreateInput,
): Promise<Subscription> {
  return apiFetch<Subscription>(BASE(organizationId), { method: "POST", body: input });
}

export function updateSubscription(
  organizationId: string,
  subscriptionId: string,
  input: SubscriptionUpdateInput,
): Promise<Subscription> {
  return apiFetch<Subscription>(`${BASE(organizationId)}/${subscriptionId}`, {
    method: "PATCH",
    body: input,
  });
}

export function deleteSubscription(organizationId: string, subscriptionId: string): Promise<void> {
  return apiFetch<void>(`${BASE(organizationId)}/${subscriptionId}`, { method: "DELETE" });
}

/** The delivery ledger, newest first, optionally narrowed. */
export async function fetchDeliveries(
  organizationId: string,
  filter: { subscriptionId?: string; status?: DeliveryRow["status"] } = {},
): Promise<DeliveryRow[]> {
  const deliveries: DeliveryRow[] = [];
  let cursor: string | undefined;
  let pages = 0;
  const params = new URLSearchParams({ limit: "100" });
  if (filter.subscriptionId) params.set("subscriptionId", filter.subscriptionId);
  if (filter.status) params.set("status", filter.status);
  do {
    const query = new URLSearchParams(params);
    if (cursor) query.set("cursor", cursor);
    const page = await pageFetch<DeliveryRow>(
      `/organizations/${organizationId}/notification-deliveries`,
      Object.fromEntries(query.entries()),
    );
    deliveries.push(...page.items);
    cursor = page.nextCursor;
    pages += 1;
  } while (cursor !== undefined && pages < 20);
  return deliveries;
}
