package sweetgirl.dataops.agentapply;

import org.springframework.jdbc.core.JdbcTemplate;
import sweetgirl.dataops.snapshot.service.ResponseDigests;

/** Read-only identity measurement. Missing pg_control_system privilege fails closed; no grants are made. */
public final class ServingDatabaseIdentity {
	public static final String WRITE_SEARCH_PATH="SET search_path TO pg_catalog, public, pg_temp";
	private ServingDatabaseIdentity() {}
	public static String measure(JdbcTemplate jdbc) {
		return jdbc.queryForObject("""
			SELECT control.system_identifier::text,d.oid::text,d.datname
			FROM pg_control_system() control JOIN pg_database d ON d.datname=current_database()
			""",(rs,index)->ResponseDigests.sha256("serving-postgres-v1\n"+rs.getString(1)+"\n"+rs.getString(2)+"\n"+rs.getString(3)+"\n"));
	}
	public static void requireMatches(String expected,String measured) {
		if (expected==null || !expected.matches("[0-9a-f]{64}") || !expected.equals(measured))
			throw new AgentApplyHold("SERVING_DATABASE_IDENTITY_CHANGED");
	}
	public static void requirePublicNamespace(JdbcTemplate jdbc) {
		Boolean matches=jdbc.queryForObject("SELECT to_regclass('store')='public.store'::regclass "
			+ "AND to_regclass('menu')='public.menu'::regclass",Boolean.class);
		if (!Boolean.TRUE.equals(matches)) throw new AgentApplyHold("SERVING_SCHEMA_IDENTITY_CHANGED");
	}
}
