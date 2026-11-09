package com.custom.morpheus.snapshotreport

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.model.Account
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.model.Instance
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.model.Snapshot
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.util.logging.Slf4j

import java.text.SimpleDateFormat

/**
 * Lists the snapshots on every instance in one tenant.
 *
 * Snapshots are found by walking tenant > instance > workload > server > snapshot. Filtering the
 * snapshot records by tenant does not work: cloud sync creates them with account set to the cloud's
 * owner (usually the Master tenant) and links them to the server only, leaving snapshot.instance empty.
 *
 * Tenant scoping: every run needs a selected tenant, and a run without one returns a prompt and no
 * data. Master tenant users may choose any tenant. Everyone else only gets their own, whatever is
 * submitted.
 */
@Slf4j
class TenantSnapshotReportProvider extends AbstractReportProvider {

	private static final String TENANT_REQUIRED = 'Select a tenant to run this report.'
	private static final int QUERY_BATCH_SIZE = 500
	private static final int ROW_BATCH_SIZE = 50
	private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L
	private static final BigDecimal BYTES_PER_GB = 1024G * 1024G * 1024G
	// Instance.snapshots arrived in plugin API 1.2 (Morpheus 8.0). On 7.0 only server links exist.
	private static final boolean INSTANCE_HAS_SNAPSHOTS = Instance.methods.any { it.name == 'getSnapshots' }

	Plugin plugin
	MorpheusContext morpheusContext
	private final TenantDirectory directory

	TenantSnapshotReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
		this.directory = new TenantDirectory(context)
	}

	@Override
	MorpheusContext getMorpheus() {
		morpheusContext
	}

	@Override
	Plugin getPlugin() {
		plugin
	}

	@Override
	String getCode() {
		'tenant-snapshot-report'
	}

	@Override
	String getName() {
		'Tenant Instance Snapshots'
	}

	@Override
	String getDescription() {
		'Lists the snapshots on every instance in a tenant.'
	}

	@Override
	String getCategory() {
		'inventory'
	}

	@Override
	Boolean getOwnerOnly() {
		false
	}

	// Subtenants may run this for themselves; process() enforces what each of them can see.
	@Override
	Boolean getMasterOnly() {
		false
	}

	@Override
	Boolean getSupportsAllZoneTypes() {
		true
	}

	@Override
	List<OptionType> getOptionTypes() {
		[
			new OptionType(
				code: 'tenant-snapshot-report-tenant',
				name: 'Tenant',
				fieldName: 'tenant',
				fieldContext: 'config',
				fieldLabel: 'Tenant',
				required: true,
				inputType: OptionType.InputType.TYPEAHEAD,
				optionSourceType: TenantDatasetProvider.NAMESPACE,
				optionSource: TenantDatasetProvider.KEY,
				displayOrder: 0
			),
			new OptionType(
				code: 'tenant-snapshot-report-include-empty',
				name: 'Include instances without snapshots',
				fieldName: 'includeEmpty',
				fieldContext: 'config',
				fieldLabel: 'Include instances without snapshots',
				inputType: OptionType.InputType.CHECKBOX,
				defaultValue: 'off',
				displayOrder: 1
			)
		]
	}

	@Override
	ServiceResponse validateOptions(Map opts) {
		// The shape of opts is undocumented, so a run is only rejected when the field is seen and blank.
		// A missing field passes here and is caught in process(), so this can never block a valid run.
		log.info("Tenant snapshot report validateOptions keys: ${opts?.keySet()}")
		List submitted = tenantValues(opts)
		if(submitted && !submitted.any { selectedTenant(it) }) {
			return ServiceResponse.error(TENANT_REQUIRED, [tenant: TENANT_REQUIRED])
		}
		ServiceResponse.success()
	}

	private List tenantValues(Object node) {
		if(!(node instanceof Map)) {
			return []
		}
		((Map) node).collectMany { key, value ->
			String name = key?.toString()
			name == 'tenant' || name?.endsWith('.tenant') ? [value] : tenantValues(value)
		}
	}

	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<Map<String, List<ReportResultRow>>> model = new ViewModel<>()
		model.object = reportRowsBySection
		getRenderer().renderTemplate('hbs/tenantSnapshots', model)
	}

	@Override
	void process(ReportResult reportResult) {
		def reports = morpheus.async.report
		reports.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()
		try {
			Map tenant = resolveTenant(reportResult)
			if(tenant.error) {
				appendRows(reportResult, [headerRow([error: tenant.error])])
			} else {
				Map result = collectSnapshotRows(tenant, isOn(reportResult.configMap?.includeEmpty))
				appendRows(reportResult, [headerRow(result.summary + [notice: tenant.notice])] + result.rows)
			}
			reports.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
		} catch(Exception e) {
			log.error("Tenant snapshot report failed: ${e.message}", e)
			reports.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
		}
	}

	/**
	 * Decides which tenant this run covers. Never falls back to a wider scope: without a known
	 * requesting tenant the report fails, and only a tenant flagged as Master may pick another.
	 */
	private Map resolveTenant(ReportResult reportResult) {
		Account requester = reportResult.account
		if(requester?.id == null) {
			throw new IllegalStateException('Report result has no tenant')
		}
		String requested = selectedTenant(reportResult.configMap?.tenant)
		if(!requested) {
			return [error: TENANT_REQUIRED]
		}
		Map own = directory.findById(requester.id) ?: [id: requester.id, name: requester.name]

		if(requester.masterAccount != true) {
			boolean askedForOther = requested != own.name && requested != own.id.toString()
			return askedForOther ? own + [notice: 'Only the Master tenant can choose a tenant, so this shows your own.'] : own
		}
		List<Map> matches = requested.isLong() ? [directory.findById(requested.toLong())].findAll() : directory.findByName(requested)
		if(!matches) {
			return [error: "No tenant found matching \"${requested}\".".toString()]
		}
		if(matches.size() > 1) {
			return [error: "More than one tenant is named \"${requested}\". Use its ID: ${matches*.id.join(', ')}.".toString()]
		}
		matches[0]
	}

	/** The typeahead normally submits the tenant's ID, but typed text and list or map values are accepted too. */
	private String selectedTenant(Object value) {
		def selected = value instanceof Collection ? value[0] : value
		if(selected instanceof Map) {
			selected = selected.value ?: selected.id
		}
		selected?.toString()?.trim()
	}

	private Map collectSnapshotRows(Map tenant, boolean includeEmpty) {
		def services = morpheus.services
		DataQuery instanceQuery = new DataQuery().withFilter('account.id', tenant.id)
		if(INSTANCE_HAS_SNAPSHOTS) {
			instanceQuery.withJoin('snapshots')
		}
		List<Instance> instances = services.instance.list(instanceQuery)
		instances = instances.sort { it.name?.toLowerCase() }

		Map<Long, Set<Long>> serverIdsByInstance = [:].withDefault { new LinkedHashSet<Long>() }
		inBatches(instances*.id) { List<Long> ids ->
			services.workload.list(new DataQuery().withFilter('instance.id', 'in', ids).withJoin('server')).each { workload ->
				if(workload.instance?.id != null && workload.server?.id != null) {
					serverIdsByInstance[workload.instance.id] << workload.server.id
				}
			}
		}

		Map<Long, ComputeServer> servers = [:]
		inBatches(serverIdsByInstance.values().flatten().unique() as List<Long>) { List<Long> ids ->
			services.computeServer.list(new DataQuery()
				.withFilter('id', 'in', ids)
				.withJoins('snapshots', 'computeServerType', 'cloud')).each { ComputeServer server ->
				if(!isSharedHost(server)) {
					servers[server.id] = server
				}
			}
		}

		// One entry per snapshot on an instance. A server link wins over a bare instance link
		// because it also tells us which server the snapshot belongs to.
		Map<Long, Map<Long, ComputeServer>> snapshotLinks = [:]
		instances.each { Instance instance ->
			Map<Long, ComputeServer> links = [:]
			if(INSTANCE_HAS_SNAPSHOTS) {
				instance.snapshots?.each { links[it.id] = null }
			}
			serverIdsByInstance[instance.id].each { Long serverId ->
				ComputeServer server = servers[serverId]
				server?.snapshots?.each { links[it.id] = server }
			}
			snapshotLinks[instance.id] = links
		}

		Map<Long, Snapshot> snapshots = [:]
		inBatches(snapshotLinks.values()*.keySet().flatten().unique() as List<Long>) { List<Long> ids ->
			services.snapshot.listById(ids).each { Snapshot snapshot -> snapshots[snapshot.id] = snapshot }
		}

		long now = System.currentTimeMillis()
		List<Map> rows = []
		int instancesWithSnapshots = 0
		instances.each { Instance instance ->
			Map<Long, ComputeServer> links = snapshotLinks[instance.id]
			List<Long> ids = links.keySet().findAll { snapshots[it] != null }.sort { Long id -> -(snapshots[id].snapshotCreated?.time ?: 0L) }
			if(ids) {
				instancesWithSnapshots++
				ids.each { Long id -> rows << snapshotRow(tenant, instance, links[id], snapshots[id], now) }
			} else if(includeEmpty) {
				rows << snapshotRow(tenant, instance, null, null, now)
			}
		}

		Date oldest = rows.findResults { it.createdDate }.min()
		rows.each { it.remove('createdDate') }
		[
			rows: rows,
			summary: [
				tenant: tenant.name,
				generated: formatDate(new Date(now)),
				instanceCount: instances.size(),
				instancesWithSnapshots: instancesWithSnapshots,
				snapshotCount: rows.count { it.snapshot },
				oldest: formatDate(oldest),
				noRows: rows.isEmpty()
			]
		]
	}

	/**
	 * Docker and hypervisor hosts run workloads for many instances, possibly for other tenants.
	 * A snapshot of the host is not a snapshot of the instance, so it stays out of the report.
	 */
	private boolean isSharedHost(ComputeServer server) {
		server.computeServerType?.containerHypervisor == true || server.computeServerType?.vmHypervisor == true
	}

	private Map snapshotRow(Map tenant, Instance instance, ComputeServer server, Snapshot snapshot, long now) {
		Date created = snapshot?.snapshotCreated
		[
			tenant: tenant.name,
			instanceId: instance.id,
			instance: instance.displayName ?: instance.name,
			instanceStatus: instance.status,
			server: server?.name,
			cloud: server?.cloud?.name,
			snapshot: snapshot?.name,
			description: snapshot?.description,
			created: formatDate(created),
			ageDays: created ? (now - created.time).intdiv(DAY_MILLIS) : null,
			sizeGb: snapshot?.maxStorage != null ? (snapshot.maxStorage / BYTES_PER_GB).setScale(2, BigDecimal.ROUND_HALF_UP) : null,
			currentlyActive: snapshot?.currentlyActive == true ? 'Yes' : (snapshot ? 'No' : null),
			createdDate: created
		]
	}

	private void appendRows(ReportResult reportResult, List<Object> rows) {
		long displayOrder = 0L
		List<ReportResultRow> records = rows.collect { row ->
			row instanceof ReportResultRow ? (ReportResultRow) row : mainRow((Map) row, displayOrder++)
		}
		records.collate(ROW_BATCH_SIZE).each { List<ReportResultRow> batch ->
			morpheus.async.report.appendResultRows(reportResult, batch).blockingGet()
		}
	}

	private ReportResultRow headerRow(Map data) {
		new ReportResultRow(section: ReportResultRow.SECTION_HEADER, displayOrder: 0L, dataMap: data)
	}

	private ReportResultRow mainRow(Map data, long displayOrder) {
		new ReportResultRow(section: ReportResultRow.SECTION_MAIN, displayOrder: displayOrder, dataMap: data)
	}

	private void inBatches(List<Long> ids, Closure work) {
		ids.collate(QUERY_BATCH_SIZE).each(work)
	}

	private boolean isOn(Object value) {
		['on', 'true', '1'].contains(value?.toString()?.toLowerCase())
	}

	private String formatDate(Date date) {
		if(date == null) {
			return null
		}
		SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'")
		format.timeZone = TimeZone.getTimeZone('UTC')
		format.format(date)
	}
}
