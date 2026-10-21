package sweetgirl.dataops.serving.client;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import sweetgirl.dataops.agentapply.AgentApplyHold;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;

/** Exact old-set replacement shared by manual and manifest paths. No distributed transaction is claimed. */
final class ExactBusinessHoursWriter {
	private final JdbcTemplate jdbc;
	ExactBusinessHoursWriter(JdbcTemplate jdbc) {this.jdbc=jdbc;}

	int replace(long storeId,String externalId,List<BusinessHourRow> expected,List<BusinessHourRow> replacement) {
		if(externalId==null || externalId.isBlank())throw new AgentApplyHold("VERIFIED_EXTERNAL_ID_REQUIRED");
		return replace(storeId,externalId,true,expected,replacement);
	}

	int replaceManual(long storeId,List<BusinessHourRow> expected,List<BusinessHourRow> replacement) {
		return replace(storeId,null,false,expected,replacement);
	}

	private int replace(long storeId,String externalId,boolean requireExternalId,List<BusinessHourRow> expected,List<BusinessHourRow> replacement) {
		if(replacement.isEmpty())throw new AgentApplyHold("EXACT_HOURS_EMPTY_REPLACEMENT");
		Integer result=jdbc.execute((ConnectionCallback<Integer>)connection->{
			if(!connection.getAutoCommit() || connection.isReadOnly())throw new AgentApplyHold("EXACT_HOURS_PRIVATE_TRANSACTION_REQUIRED");
			int originalIsolation=connection.getTransactionIsolation();
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
			connection.setAutoCommit(false);
			boolean mutationStarted=false;
			boolean transactionEnded=false;
			try {
				try(var statement=connection.createStatement()) {
					statement.execute("SET LOCAL lock_timeout='5s'");
					statement.execute("SET LOCAL statement_timeout='20s'");
				}
				String parentSql="SELECT id FROM store WHERE id=? AND status<>'DELETED'"
					+(requireExternalId ? " AND naver_place_id=?" : "")+" FOR UPDATE";
				try(var parent=connection.prepareStatement(parentSql)) {
					parent.setLong(1,storeId);if(requireExternalId)parent.setString(2,externalId);
					try(var rows=parent.executeQuery()) {if(!rows.next()){connection.rollback();transactionEnded=true;return 0;}}
				}
				// Row-exclusive is the same compatible table lock the following DELETE takes. It only
				// prevents concurrent DDL/trigger disabling; ordinary row writes remain concurrent.
				try(var statement=connection.createStatement()) {statement.execute("LOCK TABLE store_business_hour IN ROW EXCLUSIVE MODE");}
				try(var lock=connection.prepareStatement("SELECT id FROM store_business_hour WHERE store_id=? FOR UPDATE")) {
					lock.setLong(1,storeId);try(var rows=lock.executeQuery()) {while(rows.next()) {/* hold existing child rows */}}
				}
				requireForeignKey(connection);
				// A new READ COMMITTED statement after lock acquisition sees the committed result of
				// any child editor we waited for, rather than reusing its earlier statement snapshot.
				List<BusinessHourRow> current=readFresh(connection,storeId);
				if(!ServingWriter.hoursSignature(current).equals(ServingWriter.hoursSignature(expected))) {
					connection.rollback();transactionEnded=true;return 0;
				}
				mutationStarted=true;
				try(var delete=connection.prepareStatement("DELETE FROM store_business_hour WHERE store_id=?")) {
					delete.setLong(1,storeId);delete.executeUpdate();
				}
				int written=0;
				try(var insert=connection.prepareStatement("INSERT INTO store_business_hour(store_id,day_of_week,open_time,close_time,is_closed) VALUES(?,?,CAST(? AS TIME),CAST(? AS TIME),?)")) {
					for(var row:replacement) {
						insert.setLong(1,storeId);insert.setString(2,row.dayOfWeek());insert.setString(3,row.openTime());
						insert.setString(4,row.closeTime());insert.setBoolean(5,row.closed());written+=insert.executeUpdate();
					}
				}
				if(written!=replacement.size())throw new SQLException("Exact hours insert count mismatch");
				connection.commit();transactionEnded=true;return written;
			} catch(SQLException | RuntimeException failure) {
				try {connection.rollback();transactionEnded=true;} catch(SQLException rollback){failure.addSuppressed(rollback);}
				if(!mutationStarted && failure instanceof SQLException sql) {
					String code=switch(String.valueOf(sql.getSQLState())) {
						case "42501" -> "EXACT_HOURS_LOCK_PERMISSION_REQUIRED";
						case "55P03" -> "EXACT_HOURS_LOCK_TIMEOUT";
						case "57014" -> "EXACT_HOURS_STATEMENT_TIMEOUT";
						default -> "EXACT_HOURS_PREWRITE_CHECK_FAILED";
					};
					var hold=new AgentApplyHold(code);hold.initCause(failure);throw hold;
				}
				throw failure;
			} finally {
				if(transactionEnded) {
					connection.setAutoCommit(true);
					connection.setTransactionIsolation(originalIsolation);
				} else {
					// setAutoCommit(true) could commit partial work after a failed rollback. Discard the
					// physical connection instead; the caller keeps the operation UNKNOWN.
					connection.abort(Runnable::run);
				}
			}
		});
		return result==null ? 0 : result;
	}

	private static List<BusinessHourRow> readFresh(Connection connection,long storeId) throws SQLException {
		var result=new ArrayList<BusinessHourRow>();
		try(var read=connection.prepareStatement("SELECT day_of_week,open_time,close_time,is_closed FROM store_business_hour WHERE store_id=?")) {
			read.setLong(1,storeId);
			try(var rows=read.executeQuery()) {while(rows.next()) {
				Boolean closed=rows.getObject(4,Boolean.class);
				if(closed==null)throw new AgentApplyHold("EXACT_HOURS_STORED_VALUE_INVALID");
				result.add(new BusinessHourRow(rows.getString(1),exactTime(rows.getString(2)),exactTime(rows.getString(3)),closed));
			}}
		}
		return result;
	}
	private static String exactTime(String value) {
		// Preserve unusual stored seconds/fractions/24:00; they must not silently equal a minute-only preview.
		return value!=null && value.matches("[0-2][0-9]:[0-5][0-9]:00") ? value.substring(0,5) : value;
	}

	private static void requireForeignKey(Connection connection) throws SQLException {
		try(var statement=connection.prepareStatement("""
			SELECT current_setting('session_replication_role')='origin' AND EXISTS (
			 SELECT 1 FROM pg_constraint fk
			 JOIN pg_attribute child ON child.attrelid=fk.conrelid AND child.attname='store_id'
			 JOIN pg_attribute parent ON parent.attrelid=fk.confrelid AND parent.attname='id'
			 JOIN pg_class relation ON relation.oid=fk.conrelid AND relation.relkind='r'
			 WHERE fk.contype='f' AND fk.conrelid='store_business_hour'::regclass AND fk.confrelid='store'::regclass
			 AND fk.conkey=ARRAY[child.attnum] AND fk.confkey=ARRAY[parent.attnum]
			 AND fk.convalidated AND NOT fk.condeferrable
			 AND EXISTS (SELECT 1 FROM pg_trigger trigger JOIN pg_proc function ON function.oid=trigger.tgfoid
			  WHERE trigger.tgconstraint=fk.oid AND trigger.tgrelid=fk.conrelid AND trigger.tgenabled IN ('O','A')
			  AND function.proname='RI_FKey_check_ins')
			 AND EXISTS (SELECT 1 FROM pg_trigger trigger JOIN pg_proc function ON function.oid=trigger.tgfoid
			  WHERE trigger.tgconstraint=fk.oid AND trigger.tgrelid=fk.conrelid AND trigger.tgenabled IN ('O','A')
			  AND function.proname='RI_FKey_check_upd'))
			""")) {
			try(var rows=statement.executeQuery()) {
				if(!rows.next() || !rows.getBoolean(1))throw new AgentApplyHold("EXACT_HOURS_ENFORCED_FOREIGN_KEY_REQUIRED");
			}
		}
	}
}
