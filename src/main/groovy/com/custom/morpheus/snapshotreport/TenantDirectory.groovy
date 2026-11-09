package com.custom.morpheus.snapshotreport

import com.morpheusdata.core.MorpheusContext

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Tenant lookups. The plugin API has no tenant service, so these read the account table directly.
 * Callers decide who may see what; this class applies no scoping of its own.
 */
class TenantDirectory {

	private static final int MAX_SEARCH_RESULTS = 50
	private static final String SELECT_TENANT = 'SELECT id, name FROM account'

	private final MorpheusContext morpheus

	TenantDirectory(MorpheusContext morpheus) {
		this.morpheus = morpheus
	}

	Map findById(Long id) {
		query("${SELECT_TENANT} WHERE id = ?".toString(), id)[0]
	}

	List<Map> findByName(String name) {
		query("${SELECT_TENANT} WHERE name = ?".toString(), name)
	}

	/** Case-insensitive match anywhere in the name. A blank phrase lists the first tenants by name. */
	List<Map> search(String phrase) {
		String escaped = (phrase ?: '').toLowerCase().replaceAll('([!%_])', '!$1')
		query("${SELECT_TENANT} WHERE LOWER(name) LIKE ? ESCAPE '!' ORDER BY name LIMIT ${MAX_SEARCH_RESULTS}".toString(), "%${escaped}%".toString())
	}

	private List<Map> query(String sql, Object value) {
		def reports = morpheus.async.report
		Connection connection = reports.getReadOnlyDatabaseConnection().blockingGet()
		try {
			PreparedStatement statement = connection.prepareStatement(sql)
			try {
				statement.setObject(1, value)
				ResultSet results = statement.executeQuery()
				List<Map> tenants = []
				while(results.next()) {
					tenants << [id: results.getLong('id'), name: results.getString('name')]
				}
				return tenants
			} finally {
				statement.close()
			}
		} finally {
			reports.releaseDatabaseConnection(connection).blockingAwait()
		}
	}
}
