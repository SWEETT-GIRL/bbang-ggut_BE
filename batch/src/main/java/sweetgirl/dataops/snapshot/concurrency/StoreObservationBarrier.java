package sweetgirl.dataops.snapshot.concurrency;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Serializes committed raw reception/assessment with absence-based menu approval, per store.
 * Negative bigint advisory keys are reserved for this barrier. Try-locks never queue a worker
 * connection behind an approval that needs another connection for its REQUIRES_NEW claim.
 * Approval has an independent, unpooled connection so concurrent approvals cannot exhaust JPA's pool.
 * This is a DataOps ordering guarantee, not a distributed transaction with the serving database.
 */
@Component
public class StoreObservationBarrier {
	private final JdbcTemplate observationJdbc;
	private final DataSource approvalConnections;

	@Autowired
	public StoreObservationBarrier(DataSource dataSource) {
		this(dataSource, approvalConnections(dataSource));
	}

	public StoreObservationBarrier(DataSource observations, DataSource approvals) {
		this.observationJdbc = new JdbcTemplate(observations);
		this.approvalConnections = approvals;
	}

	private static DataSource approvalConnections(DataSource dataSource) {
		try {
			// Copy the actual primary pool configuration, including JDBC URL overrides and SSL properties.
			// Never fall back to an independently configured URL that could lock a different database.
			HikariDataSource primary = dataSource.unwrap(HikariDataSource.class);
			if (primary.getJdbcUrl() == null) throw new IllegalStateException("Observation barrier requires a JDBC URL-backed primary datasource");
			var properties = new Properties();
			properties.putAll(primary.getDataSourceProperties());
			properties.setProperty("connectTimeout", "5");
			properties.setProperty("socketTimeout", "10");
			properties.setProperty("tcpKeepAlive", "true");
			var connections = new DriverManagerDataSource(primary.getJdbcUrl(), primary.getUsername(), primary.getPassword());
			connections.setConnectionProperties(properties);
			return connections;
		} catch (SQLException failure) {
			throw new IllegalStateException("Observation barrier requires the primary Hikari datasource", failure);
		}
	}

	/** Called only inside the raw service's REQUIRES_NEW transaction; PostgreSQL releases at commit. */
	public void lockObservation(long storeId) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Observation barrier requires an active transaction");
		}
		Boolean locked = observationJdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, key(storeId));
		if (!Boolean.TRUE.equals(locked)) throw busy();
	}

	/** The returned scope must cover gate, claim, serving write and settlement. */
	public ApprovalScope lockApproval(long storeId) {
		Connection connection = null;
		try {
			connection = approvalConnections.getConnection();
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
				statement.setQueryTimeout(5);
				statement.setLong(1, key(storeId));
				try (var result = statement.executeQuery()) {
					if (!result.next() || !result.getBoolean(1)) throw busy();
				}
			}
			return new ApprovalScope(connection);
		} catch (SQLException | RuntimeException failure) {
			if (connection != null) {
				try { connection.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
			}
			if (failure instanceof RuntimeException runtime) throw runtime;
			throw new DataAccessResourceFailureException("Could not acquire observation barrier; approval withheld", failure);
		}
	}

	private static long key(long storeId) {
		if (storeId <= 0) throw new IllegalArgumentException("storeId must be positive");
		return Long.MIN_VALUE | storeId;
	}

	private static CannotAcquireLockException busy() {
		return new CannotAcquireLockException("Store observation or menu approval in progress; retry after completion");
	}

	public static class ApprovalScope implements AutoCloseable {
		private final Connection connection;
		private ApprovalScope(Connection connection) { this.connection = connection; }

		/** Fail closed on a known lost connection immediately before a serving operation. */
		public void assertHeld() {
			try (var statement = connection.createStatement()) {
				statement.setQueryTimeout(5);
				try (var result = statement.executeQuery("SELECT 1")) {
					if (!result.next()) throw new SQLException("Barrier connection unavailable");
				}
			} catch (SQLException failure) {
				throw new DataAccessResourceFailureException("Observation barrier connection lost; approval withheld", failure);
			}
		}

		@Override public void close() {
			try (connection) {
				connection.rollback();
			} catch (SQLException failure) {
				throw new DataAccessResourceFailureException("Could not release observation barrier", failure);
			}
		}
	}
}
