---
description: Manage the device fleet - register tester devices by hand, correct metadata, park devices, and export the provisioning CSV.
---

# Manage the device fleet

Each tenant keeps a registry of its testers' devices — the iOS UDIDs
your App Store build must be provisioned for, the Android devices
your support team correlates reports against. The registry lives one
tenant deep: Tenants — **Devices**.

![The tenant device registry](/screenshots/console-devices.png)

Why registration is manual — and why devices are never deleted — is
covered in [the security model](/concepts/security-model.md).

## Register a device

1. Open **Tenants**, click **Devices** on the tenant's row.

2. Click **Register device** and fill the form:

   - **Owner (linked tester)** — the tester this device belongs to
     (picked from the tenant's testers).
   - **Platform** — iOS or Android.
   - **Identifier (UDID / serial)** — the device's unique
     identifier, unique within the tenant. A UDID cannot be
     captured from a web page, so the tester reads it off their
     device (Settings — General — About on iOS; the vendor's
     instructions on Android) and hands it to you.
   - **Model** and **OS version** — optional descriptive fields.

3. Click **Register**.

::: tip Keep the OS version honest
The registry's descriptive fields feed the feedback context testers'
reports carry — a corrected OS version today is a better-diagnosed
bug report tomorrow.
:::

## Work the registry

- **Platform** and **Status** filters narrow the grid; **Load more**
  pages.
- **Edit** corrects the model and OS version — the descriptive facts
  may change as devices update; the identifier never does.
- **Deactivate** parks a device reversibly: a deactivated tester's
  records stay (the support history posture), and **Reactivate**
  brings the device back. Devices are never deleted.

## Export the provisioning list

**Export CSV** produces the store-side provisioning list — one row
per device with its identifier, platform, model, OS, and owner
email. Upload it to the Apple developer portal (or your MDM) when
the beta profile's device list needs a refresh.

## Devices and feedback

When a report arrives from a registered device, the release
dashboard's feedback row shows the device context automatically —
model, OS — because the registry linked it. Registration is what
turns "crashes on some iPhone" into "crashes on iPhone 13, iOS
17.4". See [triage feedback](/how-to/console/feedback-triage.md).

**Related guides:** [manage tenants](/how-to/console/tenants.md) ·
[triage feedback](/how-to/console/feedback-triage.md) ·
[tenant-admin quickstart](/roles/tenant-admin.md)
