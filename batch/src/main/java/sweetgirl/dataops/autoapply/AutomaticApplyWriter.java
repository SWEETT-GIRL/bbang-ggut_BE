package sweetgirl.dataops.autoapply;

import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import sweetgirl.dataops.serving.client.ServingWriter;

/** Private to the live-capture path. Does not enable the ordinary approval HTTP endpoint. */
public class AutomaticApplyWriter implements AutoCloseable {
	private final HikariDataSource pool;
	private final ServingWriter writer;
	public AutomaticApplyWriter(String url, String username, String password) {
		if (username == null || username.isBlank() || password == null || password.isBlank())
			throw new IllegalStateException("Automatic apply requires dedicated serving writer credentials");
		pool = new HikariDataSource();
		pool.setPoolName("dataops-automatic-writer");
		pool.setJdbcUrl(url);
		pool.setUsername(username);
		pool.setPassword(password);
		pool.setMaximumPoolSize(2);
		writer = new ServingWriter(new JdbcTemplate(pool));
	}
	public ServingWriter writer() { return writer; }
	@Override public void close() { pool.close(); }
}
