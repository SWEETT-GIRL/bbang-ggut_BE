package sweetgirl.dataops.replay;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The response file is a projection of durable DB receipts, not proof that a process exited successfully. */
@Component
@ConditionalOnProperty(name="dataops.agent.replay.enabled",havingValue="true")
public class ReplayBridgeRunner implements ApplicationRunner {
	private final JdbcVerifiedReplayAuthority authority;
	private final CaptureReplayService replay;
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;
	private final Environment environment;
	private final ApplicationContext context;

	public ReplayBridgeRunner(JdbcVerifiedReplayAuthority authority,CaptureReplayService replay,DataSource dataSource,
			ObjectMapper mapper,Environment environment,ApplicationContext context) {
		this.authority=authority;this.replay=replay;this.jdbc=new JdbcTemplate(dataSource);
		this.mapper=mapper;this.environment=environment;this.context=context;
	}

	@Override public void run(ApplicationArguments arguments) throws Exception {
		if(context instanceof WebApplicationContext || !"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type",""))
			|| environment.getProperty("dataops.serving.write-enabled",Boolean.class,false)
			|| environment.getProperty("dataops.agent.apply.enabled",Boolean.class,false)
			|| !"PROPOSAL_ONLY".equals(environment.getProperty("dataops.reconcile.mode")))
			throw new IllegalStateException("REPLAY_RUNNER_MUST_BE_NONWEB_AND_READONLY");
		String action=environment.getRequiredProperty("dataops.agent.replay.action");
		String phase="STATUS".equals(action)?environment.getRequiredProperty("dataops.agent.replay.phase"):action;
		if(!List.of("CANARY","REPLAY").contains(phase) || !List.of("CANARY","REPLAY","STATUS").contains(action))
			throw new IllegalStateException("REPLAY_ACTION_INVALID");
		Path destination=Path.of(environment.getRequiredProperty("dataops.agent.replay.receipt-path"));
		if(!destination.isAbsolute()) throw new IllegalStateException("ABSOLUTE_RECEIPT_PATH_REQUIRED");
		var binding=authority.inspectBinding();
		boolean failed=false;
		String failureCode=null;
		String failureType=null;
		String failureLocation=null;
		if(!"STATUS".equals(action)) {
			try {
				if("CANARY".equals(action)) replay.canary(binding.replayOperationId(),binding.manifestDigest());
				else replay.replay(binding.replayOperationId(),binding.manifestDigest());
			} catch(RuntimeException executionFailure) {
				failed=true; failureCode=failureCode(executionFailure);failureType=executionFailure.getClass().getSimpleName();
				for(var frame:executionFailure.getStackTrace()) if(frame.getClassName().startsWith("sweetgirl.dataops.")) {
					failureLocation=frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber();break;
				}
			}
		}
		// A failed/ambiguous action is read back. Missing item receipts remain UNKNOWN until controller reconciliation.
		ObjectNode receipt=status(binding,phase);
		receipt.put("execution_exception",failed);
		if(failureCode!=null)receipt.put("execution_error_code",failureCode);
		if(failureType!=null)receipt.put("execution_error_type",failureType);
		if(failureLocation!=null)receipt.put("execution_error_location",failureLocation);
		writeReceipt(destination,ResponseDigests.canonical(receipt,mapper)+"\n");
	}
	static String failureCode(RuntimeException failure) {
		// Domain identifiers only: raw payloads, SQL values and connection strings never enter the report.
		String message=failure.getMessage();
		if(failure instanceof IllegalStateException && message!=null && message.matches("[A-Z][A-Z0-9_]{0,95}"))return message;
		return "REPLAY_EXECUTION_FAILED";
	}

	ObjectNode status(JdbcVerifiedReplayAuthority.Binding binding,String phase) {
		var plan=binding.plan();
		var targets=plan.targets().stream().filter(t->!"CANARY".equals(phase)||plan.canaryCaptureIds().contains(t.captureId())).toList();
		var completion=jdbc.query("SELECT passed FROM response_replay_completion WHERE operation_id=? AND phase=?",
			(rs,index)->rs.getBoolean(1),plan.operationId(),phase);
		var saved=jdbc.query("SELECT capture_id,receipt_json::text FROM response_replay_receipt WHERE operation_id=? AND phase=?",
			(rs,index)->Map.entry(rs.getString(1),mapper.readTree(rs.getString(2))),plan.operationId(),phase);
		Map<String,JsonNode> byId=new HashMap<>();saved.forEach(entry->byId.put(entry.getKey(),entry.getValue()));
		if(byId.size()!=saved.size() || byId.keySet().stream().anyMatch(id->targets.stream().noneMatch(t->t.captureId().equals(id))))
			throw new IllegalStateException("REPLAY_RECEIPT_OUTSIDE_SCOPE");
		ObjectNode result=mapper.createObjectNode();
		result.put("schema_version",1).put("operation_id",binding.releaseOperationId()).put("replay_operation_id",plan.operationId().toString())
			.put("manifest_digest",binding.manifestDigest()).put("phase",phase).put("generation",binding.generation())
			.put("outcome",completion.isEmpty()?"UNKNOWN":"DONE").put("complete",!completion.isEmpty())
			.put("passed",!completion.isEmpty()&&completion.get(0)).put("operating_writes",0);
		var approvedTargets="CANARY".equals(phase)?binding.canaryPolicy().path("targets"):binding.manifestTargets();
		result.put("targets_digest",sha(approvedTargets)).put("java_targets_digest",sha(mapper.valueToTree(targets)));
		result.putObject("runtime").put("release_id",plan.releaseId()).put("artifact_sha256",plan.artifactDigest())
			.put("parser_digest",plan.parserDigest()).put("profile_digest",plan.profileDigest())
			.put("contract_digest",binding.contractDigest()).put("database_identity",binding.databaseIdentity());
		// Runtime fields describe the bound recorded execution, not a claim that a STATUS JVM is the live service.
		var items=result.putArray("items");var actual=mapper.createArrayNode();
		for(var target:targets) {
			var item=items.addObject().put("capture_id",target.captureId());JsonNode stored=byId.get(target.captureId());
			if(stored==null) {
				item.put("status",completion.isEmpty()?"UNKNOWN":"NOT_ATTEMPTED")
					.put("reason",completion.isEmpty()?"NO_DURABLE_ITEM_RECEIPT":"STOPPED_BEFORE_ITEM");
			} else {
				if(!target.captureId().equals(stored.path("captureId").asString()))throw new IllegalStateException("REPLAY_RECEIPT_ID_CHANGED");
				item.put("status",stored.path("status").asString());
				item.set("reason",stored.path("reason"));item.set("parsed_digest",stored.path("parsedDigest"));
				item.set("created_proposal_ids",stored.path("createdProposalIds"));item.set("reused_proposal_ids",stored.path("reusedProposalIds"));
				actual.addObject().put("capture_id",target.captureId()).set("parsed_digest",stored.path("parsedDigest"));
			}
		}
		result.put("result_digest",sha(actual));return result;
	}

	static void writeReceipt(Path path,String text) throws IOException {
		Path parent=path.getParent();
		if(parent==null || !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent)
			|| Files.isSymbolicLink(path)) throw new IllegalStateException("RECEIPT_PATH_UNSAFE");
		Path temporary=Files.createTempFile(parent,".replay-receipt-",".json",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
		try {
			Files.writeString(temporary,text,StandardOpenOption.TRUNCATE_EXISTING);
			try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
			Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
			try(var directory=FileChannel.open(parent,StandardOpenOption.READ)){directory.force(true);}
		} finally {Files.deleteIfExists(temporary);}
	}
	private String sha(JsonNode node){return ResponseDigests.sha256(ResponseDigests.canonical(node,mapper));}
}
