package sweetgirl.dataops.serving.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;

@EnabledIfEnvironmentVariable(named="DATAOPS_AGENT_APPLY_TEST_JDBC_URL",matches="jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/.*")
class ExactBusinessHoursWriterPostgresTest {
	private JdbcTemplate admin,jdbc;
	private DriverManagerDataSource source;
	private String schema,base;
	private ServingWriter writer;
	private boolean manual;
	private final List<BusinessHourRow> old=List.of(row("MONDAY","08:00"));
	private final List<BusinessHourRow> next=List.of(row("MONDAY","10:00"));
	private static BusinessHourRow row(String day,String open){return new BusinessHourRow(day,open,"18:00",false);}
	@BeforeEach void setup() {
		base=System.getenv("DATAOPS_AGENT_APPLY_TEST_JDBC_URL");
		admin=new JdbcTemplate(new DriverManagerDataSource(base,"postgres",""));
		schema="hours_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
		source=new DriverManagerDataSource(base+(base.contains("?")?"&":"?")+"currentSchema="+schema,"postgres","");
		jdbc=new JdbcTemplate(source);writer=new ServingWriter(jdbc);
		jdbc.execute("CREATE TABLE store(id bigint primary key,naver_place_id text,status text)");
		jdbc.execute("CREATE TABLE store_business_hour(id bigserial primary key,store_id bigint NOT NULL REFERENCES store(id),day_of_week text,open_time time,close_time time,is_closed boolean,UNIQUE(store_id,day_of_week))");
		jdbc.update("INSERT INTO store VALUES(1,'123','ACTIVE')");
		jdbc.update("INSERT INTO store_business_hour(store_id,day_of_week,open_time,close_time,is_closed) VALUES(1,'MONDAY','08:00','18:00',false)");
	}
	@AfterEach void cleanup(){admin.execute("DROP SCHEMA "+schema+" CASCADE");}
	private List<String> hours(){return jdbc.queryForList("SELECT day_of_week||':'||open_time::text FROM store_business_hour ORDER BY day_of_week",String.class);}
	private int replace(){return replaceSet(old,next);}
	private int replaceSet(List<BusinessHourRow> expected,List<BusinessHourRow> replacement) {
		return manual ? writer.replaceBusinessHours(1,expected,replacement) : writer.replaceBusinessHours(1,"123",expected,replacement);
	}

	@Test void exact_set_and_external_mapping_are_both_required() {
		assertThat(writer.replaceBusinessHours(1,"different",old,next)).isZero();
		assertThat(replace()).isEqualTo(1);
		assertThat(hours()).containsExactly("MONDAY:10:00:00");
		assertThat(replace()).isZero();
	}
	@Test void stored_seconds_and_end_of_day_are_not_silently_normalized_to_preview_minutes() {
		jdbc.update("UPDATE store_business_hour SET open_time='08:00:59'");assertThat(replace()).isZero();
		jdbc.update("UPDATE store_business_hour SET open_time='24:00:00'");
		assertThat(writer.replaceBusinessHours(1,"123",List.of(row("MONDAY","00:00")),next)).isZero();
		jdbc.update("UPDATE store_business_hour SET open_time='08:00:00',is_closed=null");
		assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_STORED_VALUE_INVALID");
	}

	@Test void waits_for_existing_child_update_then_compares_a_fresh_snapshot() throws Exception {
		assertConcurrentEditPreventsOverwrite("UPDATE store_business_hour SET open_time='09:00'",List.of("MONDAY:09:00:00"));
	}
	@Test void waits_for_existing_child_delete_then_compares_a_fresh_snapshot() throws Exception {
		assertConcurrentEditPreventsOverwrite("DELETE FROM store_business_hour",List.of());
	}
	@Test void an_inflight_new_day_insert_blocks_parent_lock_and_is_seen_before_comparison() throws Exception {
		assertConcurrentEditPreventsOverwrite("INSERT INTO store_business_hour(store_id,day_of_week,open_time,close_time,is_closed) VALUES(1,'TUESDAY','09:00','18:00',false)",List.of("MONDAY:08:00:00","TUESDAY:09:00:00"));
	}
	private void assertConcurrentEditPreventsOverwrite(String edit,List<String> expected) throws Exception {
		var worker=Executors.newSingleThreadExecutor();
		try(var editor=source.getConnection()) {
			editor.setAutoCommit(false);try(var statement=editor.createStatement()){statement.executeUpdate(edit);}
			var pending=worker.submit(this::replace);
			assertThatThrownBy(()->pending.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			editor.commit();assertThat(pending.get(5,TimeUnit.SECONDS)).isZero();assertThat(hours()).isEqualTo(expected);
		} finally {worker.shutdownNow();}
	}

	@Test void parent_fk_prevents_phantom_insert_while_replacing_an_empty_set() throws Exception {assertPhantomSerialized(true);}
	@Test void parent_fk_prevents_new_day_insert_while_replacing_existing_rows() throws Exception {assertPhantomSerialized(false);}
	private void assertPhantomSerialized(boolean empty) throws Exception {
		if(empty)jdbc.update("DELETE FROM store_business_hour");
		long key=Math.abs(UUID.randomUUID().getMostSignificantBits());
		jdbc.execute("CREATE FUNCTION pause_hours() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.day_of_week='MONDAY' THEN PERFORM pg_advisory_xact_lock("+key+"); END IF; RETURN NEW; END $$");
		jdbc.execute("CREATE TRIGGER pause_hours BEFORE INSERT ON store_business_hour FOR EACH ROW EXECUTE FUNCTION pause_hours()");
		var workers=Executors.newFixedThreadPool(2);
		try(var pause=source.getConnection()) {
			try(var statement=pause.createStatement()){statement.execute("SELECT pg_advisory_lock("+key+")");}
			var replacement=workers.submit(()->replaceSet(empty?List.of():old,next));
			boolean waiting=false;
			for(int i=0;i<100;i++) {
				waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted AND objid=CAST(? AS OID))",Boolean.class,key & 0xffffffffL));
				if(waiting)break;Thread.sleep(20);
			}
			assertThat(waiting).isTrue();
			var newDay=workers.submit(()->jdbc.update("INSERT INTO store_business_hour(store_id,day_of_week,open_time,close_time,is_closed) VALUES(1,'TUESDAY','11:00','18:00',false)"));
			assertThatThrownBy(()->newDay.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
			try(var statement=pause.createStatement()){statement.execute("SELECT pg_advisory_unlock("+key+")");}
			assertThat(replacement.get(5,TimeUnit.SECONDS)).isEqualTo(1);assertThat(newDay.get(5,TimeUnit.SECONDS)).isEqualTo(1);
			assertThat(hours()).containsExactly("MONDAY:10:00:00","TUESDAY:11:00:00");
		} finally {workers.shutdownNow();}
	}

	@Test void absent_deferred_unvalidated_or_disabled_fk_is_held_without_deleting_hours() {
		jdbc.execute("ALTER TABLE store_business_hour DROP CONSTRAINT store_business_hour_store_id_fkey");
		assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_ENFORCED_FOREIGN_KEY_REQUIRED");
		jdbc.execute("ALTER TABLE store_business_hour ADD CONSTRAINT fk_hours FOREIGN KEY(store_id) REFERENCES store(id) DEFERRABLE INITIALLY IMMEDIATE");
		assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_ENFORCED_FOREIGN_KEY_REQUIRED");
		jdbc.execute("ALTER TABLE store_business_hour DROP CONSTRAINT fk_hours");
		jdbc.execute("ALTER TABLE store_business_hour ADD CONSTRAINT fk_hours FOREIGN KEY(store_id) REFERENCES store(id) NOT VALID");
		assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_ENFORCED_FOREIGN_KEY_REQUIRED");
		jdbc.execute("ALTER TABLE store_business_hour VALIDATE CONSTRAINT fk_hours");
		jdbc.execute("ALTER TABLE store_business_hour DISABLE TRIGGER ALL");
		assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_ENFORCED_FOREIGN_KEY_REQUIRED");
		assertThat(hours()).containsExactly("MONDAY:08:00:00");
	}

	@Test void insert_failure_rolls_back_both_delete_and_prior_inserts() {
		jdbc.execute("ALTER TABLE store_business_hour ADD CHECK(day_of_week<>'TUESDAY')");
		assertThatThrownBy(()->writer.replaceBusinessHours(1,"123",old,List.of(row("MONDAY","10:00"),row("TUESDAY","11:00"))))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(hours()).containsExactly("MONDAY:08:00:00");
	}
	@Test void failed_rollback_discards_the_connection_instead_of_enabling_autocommit() throws Exception {
		jdbc.execute("ALTER TABLE store_business_hour ADD CHECK(day_of_week<>'TUESDAY')");
		try(var physical=source.getConnection()) {
			Connection broken=mock(Connection.class,delegatesTo(physical));
			doThrow(new java.sql.SQLException("simulated rollback failure","08006")).when(broken).rollback();
			var brokenJdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(broken,true));
			assertThatThrownBy(()->new ServingWriter(brokenJdbc).replaceBusinessHours(1,"123",old,List.of(row("MONDAY","10:00"),row("TUESDAY","11:00"))))
				.isInstanceOf(org.springframework.dao.DataAccessException.class);
			verify(broken).abort(any());verify(broken,never()).setAutoCommit(true);
			assertThat(hours()).containsExactly("MONDAY:08:00:00");
		}
	}

	@Test void child_lock_permission_failure_does_not_delete_and_never_grants_itself_permissions() {
		String role="hours_role_"+UUID.randomUUID().toString().replace("-","");
		admin.execute("CREATE ROLE "+role+" LOGIN");
		try {
			admin.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
			jdbc.execute("GRANT SELECT,UPDATE ON store TO "+role);
			jdbc.execute("GRANT SELECT,INSERT,DELETE ON store_business_hour TO "+role);
			var limited=new JdbcTemplate(new DriverManagerDataSource(source.getUrl(),role,""));
			assertThatThrownBy(()->new ServingWriter(limited).replaceBusinessHours(1,"123",old,next)).hasMessage("EXACT_HOURS_LOCK_PERMISSION_REQUIRED");
			assertThat(hours()).containsExactly("MONDAY:08:00:00");
		} finally {admin.execute("DROP OWNED BY "+role);admin.execute("DROP ROLE "+role);}
	}

	@Test void readiness_detects_missing_child_lock_privilege_and_minimal_column_grant() {
		String role="hours_probe_"+UUID.randomUUID().toString().replace("-","");
		admin.execute("CREATE ROLE "+role+" LOGIN");
		try {
			admin.execute("GRANT USAGE ON SCHEMA "+schema+" TO "+role);
			jdbc.execute("GRANT SELECT,UPDATE ON store TO "+role);
			jdbc.execute("GRANT SELECT,INSERT,DELETE ON store_business_hour TO "+role);
			jdbc.execute("GRANT USAGE ON ALL SEQUENCES IN SCHEMA "+schema+" TO "+role);
			try(var automatic=new sweetgirl.dataops.autoapply.AutomaticApplyWriter(source.getUrl(),role,"test-only")) {
				assertThat(automatic.hoursPermissionsReady()).isFalse();
				assertThat(hours()).containsExactly("MONDAY:08:00:00");
				jdbc.execute("GRANT UPDATE(day_of_week) ON store_business_hour TO "+role);
				assertThat(automatic.hoursPermissionsReady()).isTrue();
				assertThat(automatic.writer().replaceBusinessHours(1,"123",old,next)).isEqualTo(1);
				assertThat(hours()).containsExactly("MONDAY:10:00:00");
			}
		} finally {admin.execute("DROP OWNED BY "+role);admin.execute("DROP ROLE "+role);}
	}

	@Test void lock_timeout_rolls_back_and_the_connection_can_be_used_for_a_later_attempt() throws Exception {
		try(var editor=source.getConnection()) {
			editor.setAutoCommit(false);try(var statement=editor.createStatement()){statement.executeUpdate("UPDATE store SET status='ACTIVE' WHERE id=1");}
			assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_LOCK_TIMEOUT");
			assertThat(hours()).containsExactly("MONDAY:08:00:00");editor.rollback();
		}
		assertThat(replace()).isEqualTo(1);
	}

	@Test void manual_path_retains_store_id_authority_live_guard_and_empty_replacement_behavior() {
		manual=true;
		jdbc.update("UPDATE store SET naver_place_id='updated-mapping'");
		assertThat(replace()).isEqualTo(1);
		jdbc.update("UPDATE store SET status='DELETED'");
		assertThat(replaceSet(next,old)).isZero();
		assertThat(replaceSet(next,List.of())).isZero();
		assertThat(hours()).containsExactly("MONDAY:10:00:00");
		assertThatThrownBy(()->writer.replaceBusinessHours(1,null,next,old)).hasMessage("VERIFIED_EXTERNAL_ID_REQUIRED");
	}
	@Test void manual_path_waits_for_child_update_then_rejects_stale_old_set() throws Exception {
		manual=true;assertConcurrentEditPreventsOverwrite("UPDATE store_business_hour SET open_time='09:00'",List.of("MONDAY:09:00:00"));
	}
	@Test void manual_path_waits_for_child_delete_then_rejects_stale_old_set() throws Exception {
		manual=true;assertConcurrentEditPreventsOverwrite("DELETE FROM store_business_hour",List.of());
	}
	@Test void manual_path_waits_for_inflight_new_day_then_rejects_stale_old_set() throws Exception {
		manual=true;assertConcurrentEditPreventsOverwrite("INSERT INTO store_business_hour(store_id,day_of_week,open_time,close_time,is_closed) VALUES(1,'TUESDAY','09:00','18:00',false)",List.of("MONDAY:08:00:00","TUESDAY:09:00:00"));
	}
	@Test void manual_path_serializes_new_day_insert_while_replacing_existing_set() throws Exception {
		manual=true;assertPhantomSerialized(false);
	}
	@Test void manual_path_serializes_new_day_insert_while_replacing_empty_set() throws Exception {
		manual=true;assertPhantomSerialized(true);
	}
	@Test void manual_path_requires_an_enforced_fk_before_deleting() {
		manual=true;jdbc.execute("ALTER TABLE store_business_hour DROP CONSTRAINT store_business_hour_store_id_fkey");
		assertThatThrownBy(this::replace).hasMessage("EXACT_HOURS_ENFORCED_FOREIGN_KEY_REQUIRED");
		assertThat(hours()).containsExactly("MONDAY:08:00:00");
	}
	@Test void manual_path_insert_failure_rolls_back_delete_and_prior_inserts() {
		manual=true;jdbc.execute("ALTER TABLE store_business_hour ADD CHECK(day_of_week<>'TUESDAY')");
		assertThatThrownBy(()->replaceSet(old,List.of(row("MONDAY","10:00"),row("TUESDAY","11:00"))))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(hours()).containsExactly("MONDAY:08:00:00");
	}
}
