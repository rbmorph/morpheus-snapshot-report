package com.custom.morpheus.snapshotreport

import com.morpheusdata.core.Plugin

class TenantSnapshotReportPlugin extends Plugin {

	@Override
	String getCode() {
		'tenant-snapshot-report'
	}

	@Override
	void initialize() {
		this.setName('Tenant Snapshot Report')
		this.setDescription('Lists the snapshots on every instance in a tenant')
		this.registerProviders(
			new TenantSnapshotReportProvider(this, morpheus),
			new TenantDatasetProvider(this, morpheus)
		)
	}

	@Override
	void onDestroy() {
	}
}
