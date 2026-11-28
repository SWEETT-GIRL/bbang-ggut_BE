package sweetgirl.dataops.autoapply;

import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import sweetgirl.dataops.serving.client.ServingWriter;

/** Private to the live-capture path. Does not enable the ordinary approval HTTP endpoint. */
public class AutomaticApplyWriter implements AutoCloseable {
	private final HikariDataSource pool;
	private final ServingWriter writer;
	private final JdbcTemplate jdbc;
	public AutomaticApplyWriter(String url, String username, String password) {
		if (username == null || username.isBlank() || password == null || password.isBlank())
			throw new IllegalStateException("Automatic apply requires dedicated serving writer credentials");
		pool = new HikariDataSource();
		pool.setPoolName("dataops-automatic-writer");
		pool.setJdbcUrl(url);
		pool.setUsername(username);
		pool.setPassword(password);
		pool.setMaximumPoolSize(2);
		jdbc = new JdbcTemplate(pool);
		jdbc.setQueryTimeout(5);
		writer = new ServingWriter(new JdbcTemplate(pool));
	}
	public ServingWriter writer() { return writer; }
	/** Catalog-only probe: a positive result does not replace the transactional write-time checks. */
	public boolean hoursPermissionsReady() {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
			SELECT has_table_privilege(current_user, 'store', 'SELECT')
			 AND has_any_column_privilege(current_user, 'store', 'UPDATE')
			 AND has_table_privilege(current_user, 'store_business_hour', 'SELECT')
			 AND has_table_privilege(current_user, 'store_business_hour', 'INSERT')
			 AND has_table_privilege(current_user, 'store_business_hour', 'DELETE')
			 AND has_any_column_privilege(current_user, 'store_business_hour', 'UPDATE')
			 AND EXISTS (
			   SELECT 1 FROM pg_attribute
			   WHERE attrelid = 'store_business_hour'::regclass
			     AND attname = 'id' AND attnum > 0 AND NOT attisdropped
			     AND (attidentity IN ('a', 'd') OR COALESCE(has_sequence_privilege(
			       current_user, pg_get_serial_sequence('store_business_hour', 'id'), 'USAGE,UPDATE'), false))
			 )
			""", Boolean.class));
	}
	@Override public void close() { pool.close(); }
}
