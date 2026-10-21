package sweetgirl.dataops.proposal.service;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="DATAOPS_AGENT_APPLY_TEST_JDBC_URL",matches="jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")
class ProposalUnresolvedFencePostgresTest {
	private JdbcTemplate admin,jdbc;
	private String schema,migration;
	private final UUID operation=UUID.randomUUID();
	@BeforeEach void setup() throws Exception {
		String base=System.getenv("DATAOPS_AGENT_APPLY_TEST_JDBC_URL");admin=new JdbcTemplate(new DriverManagerDataSource(base,"postgres",""));
		schema="fence_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
		jdbc=new JdbcTemplate(new DriverManagerDataSource(base+(base.contains("?")?"&":"?")+"currentSchema="+schema,"postgres",""));
		jdbc.execute("CREATE TABLE change_proposal(id bigint primary key,store_id bigint,target text,status text,operation_id uuid,old_value jsonb,new_value jsonb,producer text)");
		jdbc.execute("CREATE UNIQUE INDEX uq_proposal_in_flight ON change_proposal(store_id,target) WHERE status IN ('PENDING','APPLYING')");
		migration=Files.readString(Path.of("src/main/resources/db/dataops-migration/V20261014220000__proposal_unresolved_fence.sql"));
	}
	@AfterEach void cleanup(){admin.execute("DROP SCHEMA "+schema+" CASCADE");}
	private void insert(long id,String status,String producer){jdbc.update("INSERT INTO change_proposal VALUES(?,1,'store.phone',?,?,null,'\"new\"',?)",id,status,operation,producer);}

	@Test void migration_does_not_silently_resolve_unknown_pending_conflicts() {
		insert(1,"UNKNOWN","FIRST");insert(2,"PENDING","SECOND");
		assertThatThrownBy(()->jdbc.execute(migration)).hasMessageContaining("UNRESOLVED_FENCE_PREFLIGHT");
		assertThat(jdbc.queryForList("SELECT status FROM change_proposal ORDER BY id",String.class)).containsExactly("UNKNOWN","PENDING");
	}
	@Test void migration_refuses_unresolved_rows_without_operation_identity() {
		jdbc.update("INSERT INTO change_proposal(id,store_id,target,status) VALUES(1,1,'store.phone','UNKNOWN')");
		assertThatThrownBy(()->jdbc.execute(migration)).hasMessageContaining("UNRESOLVED_FENCE_PREFLIGHT");
	}
	@Test void unknown_blocks_other_producer_proposal_and_unscoped_claim_targets() throws Exception {
		insert(1,"UNKNOWN","FIRST");jdbc.execute(migration);
		assertThatThrownBy(()->insert(2,"PENDING","SECOND")).hasMessageContaining("uq_proposal_in_flight");
		assertThatThrownBy(()->insert(3,"APPLYING","THIRD")).hasMessageContaining("uq_proposal_in_flight");
		jdbc.update("INSERT INTO change_proposal VALUES(4,1,'store.phone','FAILED',?,null,'\"other\"','SECOND')",UUID.randomUUID());
		String sql=sweetgirl.dataops.proposal.repository.ChangeProposalRepository.class.getMethod("hasUnresolvedTarget",Long.class)
			.getAnnotation(org.springframework.data.jpa.repository.Query.class).value().replace(":id","?");
		assertThat(jdbc.queryForObject(sql,Boolean.class,4L)).isTrue();
		// Scope is exactly store+target, not a claim to fence every column on the same physical row.
		jdbc.update("INSERT INTO change_proposal(id,store_id,target,status) VALUES(5,1,'store.name','PENDING')");
	}
	@Test void applying_and_unknown_cannot_be_deleted_reset_or_moved_to_another_target() {
		insert(1,"APPLYING","FIRST");jdbc.execute(migration);
		assertThatThrownBy(()->jdbc.update("UPDATE change_proposal SET status='PENDING' WHERE id=1")).hasMessageContaining("UNRESOLVED_TARGET_FENCE");
		jdbc.update("UPDATE change_proposal SET status='UNKNOWN' WHERE id=1");
		for(String change:new String[]{"status='APPROVED'","status='FAILED'","status='APPLYING'","target='store.name'","producer='SECOND'","new_value='null'"})
			assertThatThrownBy(()->jdbc.update("UPDATE change_proposal SET "+change+" WHERE id=1")).hasMessageContaining("UNRESOLVED_TARGET_FENCE");
		assertThatThrownBy(()->jdbc.update("DELETE FROM change_proposal WHERE id=1")).hasMessageContaining("UNRESOLVED_TARGET_FENCE");
	}
	@Test void only_matching_immutable_reconciliation_releases_the_same_operation_fence() {
		insert(1,"UNKNOWN","FIRST");jdbc.execute(migration);
		String digest="a".repeat(64);UUID evidence=UUID.randomUUID();
		jdbc.update("INSERT INTO proposal_apply_reconciliation VALUES(?,1,?,'NOT_APPLIED','verified-test-event',?,?,?,'{}',CURRENT_TIMESTAMP)",evidence,operation,digest,digest,digest);
		assertThatThrownBy(()->jdbc.update("UPDATE change_proposal SET status='APPROVED' WHERE id=1")).hasMessageContaining("UNRESOLVED_TARGET_FENCE");
		jdbc.update("UPDATE change_proposal SET status='FAILED' WHERE id=1");insert(2,"PENDING","SECOND");
		assertThatThrownBy(()->jdbc.update("DELETE FROM proposal_apply_reconciliation WHERE evidence_id=?",evidence)).hasMessageContaining("immutable");
	}
}
