/**
 * Portal home — the My Applications catalog (issue #36, S8-02,
 * FR-012/PRD 31). The server computes the authorized set (the decision
 * API's self-scoped catalog, PRD 26: never a client-side filter); this
 * screen renders its pages with useInfiniteQuery plus a Load more
 * button on the cursor envelope, a manual refresh, and the honest
 * states: loading skeletons, the deny-by-default EMPTY catalog (a
 * grantless tester sees nothing here by design, INV-005 — not an
 * error), and the problem+json error card with a retry.
 */
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import RefreshIcon from "@mui/icons-material/Refresh";
import SmartphoneIcon from "@mui/icons-material/Smartphone";
import {
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  CircularProgress,
  Stack,
  Typography,
} from "@mui/material";
import { useState } from "react";
import { Link as RouterLink } from "react-router-dom";
import { ApiError } from "../api/problem";
import { fetchCatalogPage, type CatalogCard } from "../api/catalog";
import { useAuth } from "../auth/AuthContext";

/** Cached renders are instant (AC5); a refresh revalidates on demand. */
const CATALOG_STALE_TIME_MS = 30_000;

export default function HomePage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const firstName = (user?.fullName ?? "").trim().split(/\s+/)[0];
  const [refreshing, setRefreshing] = useState(false);

  const catalog = useInfiniteQuery({
    queryKey: ["catalog"],
    queryFn: ({ pageParam }) => fetchCatalogPage({ cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.next_cursor,
    staleTime: CATALOG_STALE_TIME_MS,
  });

  const cards = catalog.data?.pages.flatMap((page) => page.items) ?? [];

  async function handleRefresh() {
    setRefreshing(true);
    try {
      // Invalidation refetches the active query — one refetch, not two
      // (the #36 review's double-refetch finding, folded by #37).
      await queryClient.invalidateQueries({ queryKey: ["catalog"] });
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={1} sx={{ alignItems: "center", justifyContent: "space-between" }}>
        <Typography variant="h5" component="h1" data-testid="portal-home-title">
          {firstName ? `Welcome, ${firstName}` : "Welcome"}
        </Typography>
        <Button
          size="small"
          startIcon={<RefreshIcon />}
          onClick={handleRefresh}
          disabled={refreshing || catalog.isPending}
          data-testid="catalog-refresh"
        >
          {refreshing ? "Refreshing" : "Refresh"}
        </Button>
      </Stack>

      {catalog.isPending ? <CatalogLoading /> : null}

      {catalog.isError ? <CatalogError error={catalog.error} onRetry={() => catalog.refetch()} /> : null}

      {catalog.isSuccess && cards.length === 0 ? <CatalogEmpty /> : null}

      {catalog.isSuccess && cards.length > 0 ? (
        <Stack spacing={2} data-testid="catalog-list">
          {cards.map((card) => (
            <CatalogCardView key={card.application.applicationId} card={card} />
          ))}
          {catalog.hasNextPage ? (
            <Button
              variant="outlined"
              onClick={() => catalog.fetchNextPage()}
              disabled={catalog.isFetchingNextPage}
              data-testid="catalog-load-more"
            >
              {catalog.isFetchingNextPage ? <CircularProgress size={20} sx={{ mr: 1 }} /> : null}
              Load more
            </Button>
          ) : null}
        </Stack>
      ) : null}
    </Stack>
  );
}

/** One application card: name, latest authorized version, per-platform
 * availability — and the navigation into the S8-03 version history. */
function CatalogCardView({ card }: { card: CatalogCard }) {
  const { application, latestAuthorizedVersion, platforms } = card;
  const archived = application.status === "ARCHIVED";
  return (
    <Card
      data-testid={`catalog-card-${application.applicationId}`}
      component={RouterLink}
      to={`/applications/${card.tenantId}/${application.applicationId}`}
      sx={{ textDecoration: "none", color: "inherit" }}
    >
      <CardContent>
        <Stack spacing={1}>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <SmartphoneIcon color="primary" />
            <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>
              {application.name}
            </Typography>
            {archived ? <Chip size="small" label="Archived" color="warning" /> : null}
          </Stack>
          <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <Typography variant="body1" data-testid="catalog-version">
              {latestAuthorizedVersion.versionName}
            </Typography>
            <Chip
              size="small"
              variant="outlined"
              label={latestAuthorizedVersion.status}
              data-testid="catalog-version-status"
            />
          </Stack>
          <Stack direction="row" spacing={1} useFlexGap sx={{ flexWrap: "wrap" }}>
            {platforms.map((platform) => (
              <Chip
                key={platform.platform}
                size="small"
                color="primary"
                variant="outlined"
                label={
                  platform.versionId === latestAuthorizedVersion.versionId
                    ? platformLabel(platform.platform)
                    : `${platformLabel(platform.platform)} ${platform.versionName}`
                }
                data-testid={`catalog-platform-${platform.platform}`}
              />
            ))}
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}

function platformLabel(platform: CatalogCard["platforms"][number]["platform"]): string {
  return platform === "IOS" ? "iOS" : "Android";
}

function CatalogLoading() {
  return (
    <Box data-testid="catalog-loading" role="status" aria-label="Loading your applications">
      <CircularProgress size={28} sx={{ display: "block", mx: "auto", my: 4 }} />
    </Box>
  );
}

/** The deny-by-default resting state: nothing shared yet is not an error. */
function CatalogEmpty() {
  return (
    <Card data-testid="catalog-empty">
      <CardContent>
        <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 3 }}>
          <SmartphoneIcon color="primary" sx={{ fontSize: 40 }} />
          <Typography variant="h6">My Applications</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 360 }}>
            Apps you are authorized to test will appear here. Nothing is
            shared with you yet — ask the team to add you to a tenant or a
            tester group.
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  );
}

/** API failures render the problem envelope honestly, with a retry. */
function CatalogError({ error, onRetry }: { error: unknown; onRetry: () => void }) {
  const apiError = error instanceof ApiError ? error : null;
  return (
    <Card data-testid="catalog-error">
      <CardContent>
        <Stack spacing={1.5} sx={{ alignItems: "center", textAlign: "center", py: 3 }}>
          <Typography variant="h6">Could not load your applications</Typography>
          <Typography variant="body2" color="text.secondary" data-testid="catalog-error-detail">
            {apiError
              ? `${apiError.title ?? "Request failed"}${apiError.code ? ` (${apiError.code})` : ""}`
              : "Unexpected error. Please retry."}
          </Typography>
          {apiError?.traceId ? (
            <Typography variant="caption" color="text.secondary">
              trace {apiError.traceId}
            </Typography>
          ) : null}
          <Button variant="contained" size="small" onClick={onRetry} data-testid="catalog-error-retry">
            Retry
          </Button>
        </Stack>
      </CardContent>
    </Card>
  );
}

