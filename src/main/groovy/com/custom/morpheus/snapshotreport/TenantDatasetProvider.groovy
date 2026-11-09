package com.custom.morpheus.snapshotreport

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.model.projection.AccountIdentity
import io.reactivex.rxjava3.core.Observable

/**
 * Feeds the report's Tenant typeahead. Master tenant users can search every tenant. Anyone else
 * only ever gets their own, whatever they type. With no known user it returns nothing.
 */
class TenantDatasetProvider extends AbstractDatasetProvider<Map, Long> {

	static final String NAMESPACE = 'tenantSnapshots'
	static final String KEY = 'tenants'

	private final TenantDirectory directory

	TenantDatasetProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
		this.directory = new TenantDirectory(context)
	}

	@Override
	DatasetInfo getInfo() {
		new DatasetInfo(NAMESPACE, KEY, 'Report Tenants', 'Tenants the current user can run the snapshot report for')
	}

	@Override
	Class<Map> getItemType() {
		Map
	}

	@Override
	Observable<Map> list(DatasetQuery query) {
		Observable.fromIterable(visibleTenants(query))
	}

	@Override
	Observable<Map> listOptions(DatasetQuery query) {
		Observable.fromIterable(visibleTenants(query).collect { Map tenant -> [name: tenant.name, value: tenant.id] as Map })
	}

	@Override
	Map fetchItem(Object value) {
		value?.toString()?.isLong() ? item(value.toString().toLong()) : null
	}

	@Override
	Map item(Long value) {
		directory.findById(value)
	}

	@Override
	String itemName(Map item) {
		item.name
	}

	@Override
	Long itemValue(Map item) {
		(Long) item.id
	}

	private List<Map> visibleTenants(DatasetQuery query) {
		AccountIdentity account = query.user?.account ?: query.account
		if(account?.id == null) {
			return []
		}
		if(account.masterAccount != true) {
			return [directory.findById(account.id)].findAll()
		}
		directory.search([query.phrase, query.get('phrase'), query.get('query')].find()?.toString()?.trim())
	}
}
