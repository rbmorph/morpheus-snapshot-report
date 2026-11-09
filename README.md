# Tenant Snapshot Report

A Morpheus report that lists the snapshots on every instance in a tenant.

## Build

Needs JDK 11 or newer. Internet access is required to download dependencies.

    ./gradlew jar

The plugin is written to `build/libs/tenant-snapshot-report-1.4.0.jar`.

It is built against plugin API 1.1.6 and declares Morpheus 7.0.0 as its minimum. To target a
newer appliance, change `morphPluginApiVersion` in `gradle.properties`.

## Install

1. Go to **Administration > Integrations > Plugins** and upload the jar.
2. Go to **Operations > Reports** and find **Tenant Instance Snapshots** in the Inventory category.

## Filters

- **Tenant (required):** a typeahead. Master tenant users can search every tenant. Subtenant users
  can only select their own: the search returns only that tenant, and the report enforces it again
  when it runs, whatever is submitted. A run with no tenant returns no data and asks for one.
- **Include instances without snapshots:** also adds a blank row for each instance with none.

## What to check on first run

Compare one tenant against the Snapshots tab of a few instances, covering each cloud type you use.

- **Typeahead shows nothing for a Master user:** open `/options/tenantSnapshots/tenants?phrase=a` while
  logged in. If that lists tenants, the report form isn't calling the dataset. If it's empty, the
  appliance isn't passing the user to the dataset.
- **Master can't reach other tenants:** the appliance isn't reporting the Master flag on the report's
  tenant. The report stays limited to your own tenant in that case.
- **Form still shows the old text box after upgrading:** remove the plugin and upload it again.
- **Blank runs aren't rejected on the form:** search `morpheus-ui` logs for
  `validateOptions keys`. If the line is missing, Morpheus isn't calling validation for this report.
  If it's there, the keys show how the form submits the field. Blank runs still return no data.
- **Fewer snapshots than expected:** check `morpheus-ui` logs for errors from
  `TenantSnapshotReportProvider`, then compare with `GET /api/instances/:id/snapshots`.

## Known limits

- Tenant means the instance's owner. Instances shared into a tenant aren't included.
- Snapshots of Docker and hypervisor hosts are left out, because a host serves many instances.
- The report form is the same for every user, so the plugin can't pre-fill it per user.
- Size is blank for snapshots where the cloud plugin doesn't record one.
- Every user who can run reports in a tenant sees all of that tenant's instances, regardless of
  group access.
