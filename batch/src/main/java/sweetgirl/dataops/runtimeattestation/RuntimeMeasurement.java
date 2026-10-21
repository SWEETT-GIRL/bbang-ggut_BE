package sweetgirl.dataops.runtimeattestation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import sweetgirl.dataops.agentapply.ServingDatabaseIdentity;
import sweetgirl.dataops.reconcile.service.ReconcileService;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.MenuStructureProfile;
import sweetgirl.dataops.snapshot.parser.ParsedMenu;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal;
import sweetgirl.dataops.snapshot.parser.StoreSignalParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import sweetgirl.dataops.sync.enums.SyncSource;
import tools.jackson.databind.ObjectMapper;

/** Measures this process and its actual connections. No release identity is taken from a caller's JSON. */
@Component
public class RuntimeMeasurement {

	private final JdbcTemplate dataops;
	private final Supplier<JdbcTemplate> serving;
	private final ResponseDiagnostics diagnostics;
	private final Supplier<String> policy;
	private final Supplier<Path> artifact;
	private final ObjectMapper mapper;

	@Autowired
	public RuntimeMeasurement(DataSource primary, @Qualifier("prodJdbcTemplate") ObjectProvider<JdbcTemplate> serving,
			ResponseDiagnostics diagnostics, ObjectProvider<ReconcileService> reconcile, ObjectMapper mapper) {
		this(new JdbcTemplate(primary), serving::getIfAvailable, diagnostics,
			() -> reconcile.getObject().replayPolicyDigest(), RuntimeMeasurement::executingArtifact, mapper);
	}

	RuntimeMeasurement(JdbcTemplate dataops, Supplier<JdbcTemplate> serving, ResponseDiagnostics diagnostics,
			Supplier<String> policy, Supplier<Path> artifact, ObjectMapper mapper) {
		this.dataops=dataops; this.serving=serving; this.diagnostics=diagnostics;
		this.policy=policy; this.artifact=artifact; this.mapper=mapper;
	}

	public record Measurement(String artifactSha256, String parserDigest, String profileDigest,
		String contractDigest, String contractVersion, String policyDigest, String dataopsDatabaseIdentity,
		String servingDatabaseIdentity, String databaseIdentity) {}

	public Measurement measure() {
		JdbcTemplate reader=serving.get();
		if (reader==null) throw new IllegalStateException("SERVING_MEASUREMENT_UNAVAILABLE");
		String dataopsId=ServingDatabaseIdentity.measure(dataops);
		String servingId=ServingDatabaseIdentity.measure(reader);
		String combined=ResponseDigests.sha256(ResponseDigests.canonical(
			mapper.valueToTree(Map.of("dataops",dataopsId,"serving",servingId)),mapper));
		return new Measurement(artifactDigest(artifact.get()),diagnostics.runtimeParserDigest(),
			diagnostics.structureProfile().digest(),semanticContractDigest(),ResponseDiagnostics.CONTRACT,
			policy.get(),dataopsId,servingId,combined);
	}

	public static Path executingArtifact() {
		var source=new ApplicationHome(RuntimeMeasurement.class).getSource();
		if (source==null) throw new IllegalStateException("EXECUTING_JAR_REQUIRED");
		return source.toPath();
	}

	public static String artifactDigest(Path path) {
		if (path==null || !path.getFileName().toString().endsWith(".jar")
			|| !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("EXECUTING_JAR_REQUIRED");
		try (var input=Files.newInputStream(path)) {
			var hash=MessageDigest.getInstance("SHA-256");
			byte[] buffer=new byte[65536]; int size;
			while((size=input.read(buffer))!=-1) hash.update(buffer,0,size);
			return HexFormat.of().formatHex(hash.digest());
		} catch (IOException | java.security.NoSuchAlgorithmException failure) {
			throw new IllegalStateException("EXECUTING_JAR_UNREADABLE",failure);
		}
	}

	/** Runtime contract identity is deliberately distinct from E1's protected-source digest. */
	public static String semanticContractDigest() {
		var classes=new TreeMap<String,Class<?>>();
		for(Class<?> type:new Class<?>[]{ResponseDiagnostics.class,ResponseDigests.class,MenuStructureProfile.class,
			MenuEvidenceParser.class,BusinessHoursParser.class,BusinessHourRow.class,ParsedPlace.class,ParsedMenu.class,
			StoreSignalParser.class,ParsedStoreSignal.class,SnapshotSubmitRequest.class,SyncSource.class}) collect(type,classes);
		try {
			var hash=MessageDigest.getInstance("SHA-256");
			hash.update(("response-runtime-contract-v1\n"+ResponseDiagnostics.CONTRACT+"\n").getBytes(StandardCharsets.UTF_8));
			for(var entry:classes.entrySet()) {
				hash.update((entry.getKey()+"\0").getBytes(StandardCharsets.UTF_8));
				try(var input=entry.getValue().getResourceAsStream("/"+entry.getKey().replace('.','/')+".class")) {
					if(input==null) throw new IllegalStateException("CONTRACT_CLASS_UNAVAILABLE");
					hash.update(input.readAllBytes());
				}
			}
			return HexFormat.of().formatHex(hash.digest());
		} catch(IOException | java.security.NoSuchAlgorithmException failure) {
			throw new IllegalStateException("CONTRACT_MEASUREMENT_FAILED",failure);
		}
	}

	private static void collect(Class<?> type,Map<String,Class<?>> classes) {
		if(classes.putIfAbsent(type.getName(),type)!=null) return;
		for(Class<?> nested:type.getDeclaredClasses()) collect(nested,classes);
	}
}
