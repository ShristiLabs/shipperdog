/**
 * Shared create/edit application form (issue #16). Field rules mirror
 * the API (applicationRules.ts): name, optional description, at least
 * one platform, and per-platform identifiers required exactly when
 * their platform is chosen. The ownership scope follows PRD 14:
 * registration is born TENANT-owned under a picked owner tenant or
 * ORGANIZATION-shared under the signed-in organization; on edit the
 * scope select requests the audited transition — converting a shared
 * application to tenant ownership additionally picks the new owner
 * tenant (and is blocked server-side while ACTIVE tenant
 * authorizations remain: revoke first).
 */
import {
  Autocomplete,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormHelperText,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
} from "@mui/material";
import { useState } from "react";
import type { Tenant } from "../api/tenants";
import {
  APPLICATION_PLATFORMS,
  type Application,
  type ApplicationPlatform,
  type OwnershipScope,
} from "../api/applications";
import { useToast } from "../components/ToastContext";
import {
  validateApplicationDescription,
  validateApplicationName,
  validatePlatformIdentifier,
} from "./applicationRules";

export interface ApplicationFormValue {
  name: string;
  description: string;
  platforms: ApplicationPlatform[];
  bundleId: string;
  packageName: string;
  ownershipScope: OwnershipScope;
  ownerTenantId: string | null;
}

interface ApplicationDialogProps {
  open: boolean;
  title: string;
  submitLabel: string;
  mode: "create" | "edit";
  /** Edit seed; null in create mode. */
  application?: Application | null;
  /** Pre-selected owner tenant for create mode (the screen's context). */
  defaultOwnerTenantId?: string | null;
  tenants: Tenant[];
  tenantsLoading?: boolean;
  onClose: () => void;
  onSubmit: (value: ApplicationFormValue) => Promise<void>;
}

const PLATFORM_LABELS: Record<ApplicationPlatform, string> = {
  IOS: "iOS",
  ANDROID: "Android",
};

export default function ApplicationDialog({
  open,
  title,
  submitLabel,
  mode,
  application = null,
  defaultOwnerTenantId = null,
  tenants,
  tenantsLoading = false,
  onClose,
  onSubmit,
}: ApplicationDialogProps) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [platforms, setPlatforms] = useState<ApplicationPlatform[]>([]);
  const [bundleId, setBundleId] = useState("");
  const [packageName, setPackageName] = useState("");
  const [ownershipScope, setOwnershipScope] = useState<OwnershipScope>("TENANT");
  const [ownerTenantId, setOwnerTenantId] = useState<string | null>(defaultOwnerTenantId);
  const [touched, setTouched] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  // Re-seed the form whenever a different application opens the dialog.
  const [seed, setSeed] = useState("");
  const currentSeed = `${mode}:${application?.id ?? "new"}`;
  if (open && seed !== currentSeed) {
    setSeed(currentSeed);
    setName(application?.name ?? "");
    setDescription(application?.description ?? "");
    setPlatforms(application?.platforms ? [...application.platforms] : []);
    setBundleId(application?.bundleId ?? "");
    setPackageName(application?.packageName ?? "");
    setOwnershipScope(application?.ownershipScope ?? "TENANT");
    setOwnerTenantId(mode === "create" ? defaultOwnerTenantId : null);
    setTouched(false);
  }

  const iosChosen = platforms.includes("IOS");
  const androidChosen = platforms.includes("ANDROID");
  const nameError = touched ? validateApplicationName(name) : undefined;
  const descriptionError = touched ? validateApplicationDescription(description) : undefined;
  const platformsError = touched && platforms.length === 0 ? "At least one platform is required" : undefined;
  const bundleError = touched ? identifierError(bundleId, "bundleId", iosChosen) : undefined;
  const packageError = touched ? identifierError(packageName, "packageName", androidChosen) : undefined;
  /** Converting a shared application to tenant ownership requires the
   * new owner; a TENANT-born application never re-picks its owner
   * (re-owning between tenants is not a registry operation). */
  const ownerPickerShown =
    ownershipScope === "TENANT" && (mode === "create" || application?.ownershipScope === "ORGANIZATION");
  const ownerTenant = tenants.find((tenant) => tenant.id === ownerTenantId) ?? null;
  const ownerError =
    touched && ownerPickerShown && ownerTenantId === null
      ? "The owner tenant is required for tenant ownership"
      : undefined;

  const valid =
    validateApplicationName(name) === undefined &&
    validateApplicationDescription(description) === undefined &&
    platforms.length > 0 &&
    identifierError(bundleId, "bundleId", iosChosen) === undefined &&
    identifierError(packageName, "packageName", androidChosen) === undefined &&
    (!ownerPickerShown || ownerTenantId !== null);

  async function handleSubmit() {
    setTouched(true);
    if (!valid) return;
    setSubmitting(true);
    try {
      await onSubmit({
        name: name.trim(),
        description: description.trim(),
        platforms,
        bundleId: bundleId.trim(),
        packageName: packageName.trim(),
        ownershipScope,
        ownerTenantId,
      });
      onClose();
      setTouched(false);
    } catch (error) {
      toast.showError(error);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ marginTop: 1 }}>
          <TextField
            label="Name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            error={nameError !== undefined}
            helperText={nameError ?? "Within-organization identifier (1-200 characters)"}
            required
            slotProps={{ htmlInput: { "data-testid": "app-name" } }}
          />
          <TextField
            label="Description"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            error={descriptionError !== undefined}
            helperText={descriptionError ?? "Optional (max 2000 characters)"}
            multiline
            minRows={2}
            slotProps={{ htmlInput: { "data-testid": "app-description" } }}
          />
          <Stack spacing={1}>
            <ToggleButtonGroup
              value={platforms}
              onChange={(_event, value: ApplicationPlatform[]) => setPlatforms(value)}
              aria-label="Platforms"
              color="primary"
              data-testid="app-platforms"
            >
              {APPLICATION_PLATFORMS.map((platform) => (
                <ToggleButton
                  key={platform}
                  value={platform}
                  data-testid={`platform-${platform.toLowerCase()}`}
                >
                  {PLATFORM_LABELS[platform]}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
            {platformsError !== undefined || bundleError !== undefined || packageError !== undefined ? (
              <FormHelperText error data-testid="platforms-helper">
                {[platformsError, bundleError, packageError].find((error) => error !== undefined)}
              </FormHelperText>
            ) : (
              <FormHelperText>At least one platform; its identifier becomes required</FormHelperText>
            )}
          </Stack>
          <TextField
            label="Bundle ID (iOS)"
            value={bundleId}
            onChange={(event) => setBundleId(event.target.value)}
            error={bundleError !== undefined}
            helperText={bundleError ?? "com.example.app — required when iOS is chosen"}
            disabled={!iosChosen}
            slotProps={{ htmlInput: { "data-testid": "app-bundle-id" } }}
          />
          <TextField
            label="Package name (Android)"
            value={packageName}
            onChange={(event) => setPackageName(event.target.value)}
            error={packageError !== undefined}
            helperText={packageError ?? "com.example.app — required when Android is chosen"}
            disabled={!androidChosen}
            slotProps={{ htmlInput: { "data-testid": "app-package-name" } }}
          />
          <TextField
            select
            label="Ownership"
            value={ownershipScope}
            onChange={(event) => setOwnershipScope(event.target.value as OwnershipScope)}
            helperText={
              ownershipScope === "TENANT"
                ? "Belongs to one tenant of the organization"
                : "Owned by the organization; tenants receive it through authorization"
            }
            required
            data-testid="app-scope"
          >
            <MenuItem value="TENANT">Tenant-owned</MenuItem>
            <MenuItem value="ORGANIZATION">Organization-shared</MenuItem>
          </TextField>
          {ownerPickerShown ? (
            <Autocomplete
              options={tenants}
              loading={tenantsLoading}
              getOptionLabel={(option: Tenant) => option.name}
              isOptionEqualToValue={(option: Tenant, value: Tenant) => option.id === value.id}
              value={ownerTenant}
              onChange={(_event, value: Tenant | null) => setOwnerTenantId(value?.id ?? null)}
              renderInput={(params) => (
                <TextField
                  {...params}
                  label="Owner tenant"
                  error={ownerError !== undefined}
                  helperText={ownerError ?? "The tenant this application belongs to"}
                  required
                />
              )}
              data-testid="app-owner-tenant"
            />
          ) : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          onClick={() => void handleSubmit()}
          disabled={submitting || (touched && !valid)}
          data-testid="application-submit"
        >
          {submitting ? "Saving..." : submitLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/** The per-platform coupling rule: required when chosen, forbidden
 * otherwise (the server enforces the same pair on both arms). */
function identifierError(
  value: string,
  field: "bundleId" | "packageName",
  chosen: boolean,
): string | undefined {
  if (!chosen) {
    return value.trim() !== "" ? `${field} is set but its platform is not chosen` : undefined;
  }
  return validatePlatformIdentifier(value, field);
}
