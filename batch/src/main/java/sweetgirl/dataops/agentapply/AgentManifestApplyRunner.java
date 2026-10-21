package sweetgirl.dataops.agentapply;

import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import com.zaxxer.hikari.HikariDataSource;

import sweetgirl.dataops.proposal.service.ProposalCommandService;
import sweetgirl.dataops.replay.VerifiedReplayAuthority;
import sweetgirl.dataops.serving.client.ServingWriter;
import sweetgirl.dataops.serving.config.ProdSchemaGuard;

/** A one-manifest, non-web process. Credentials and approval IDs come only from trusted host configuration. */
@Component
@ConditionalOnProperty(name="dataops.agent.apply.enabled",havingValue="true")
public class AgentManifestApplyRunner implements ApplicationRunner {
	private final Environment environment;
	private final ApplicationContext context;
	private final AgentManifestApplyGate gate;
	private final ProposalCommandService proposals;
	private final ObjectProvider<VerifiedReplayAuthority> authority;

	public AgentManifestApplyRunner(Environment environment,ApplicationContext context,AgentManifestApplyGate gate,
			ProposalCommandService proposals,ObjectProvider<VerifiedReplayAuthority> authority) {
		this.environment=environment; this.context=context; this.gate=gate; this.proposals=proposals; this.authority=authority;
	}

	@Override public void run(ApplicationArguments arguments) {
		if (context instanceof WebApplicationContext
			|| !"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type",""))
			|| environment.getProperty("dataops.serving.write-enabled",Boolean.class,false)
			|| !"PROPOSAL_ONLY".equals(environment.getProperty("dataops.reconcile.mode")))
			throw new AgentApplyHold("MANIFEST_RUNNER_MUST_BE_NONWEB_AND_SCOPED");
		VerifiedReplayAuthority attestor=authority.getIfAvailable();
		if (attestor == null) throw new AgentApplyHold("TRUSTED_RUNTIME_ATTESTOR_REQUIRED");
		try(var scope=attestor.openApplyScope()) {
			scope.assertHeld();
			var identity=attestor.currentRuntime();
			if (identity==null || identity.servingDatabaseIdentity()==null)
				throw new AgentApplyHold("TRUSTED_SERVING_IDENTITY_REQUIRED");
			try (var writeDataSource = new HikariDataSource()) {
				writeDataSource.setPoolName("manifest-private-writer");
				writeDataSource.setMaximumPoolSize(1);
				writeDataSource.setConnectionInitSql(ServingDatabaseIdentity.WRITE_SEARCH_PATH);
				writeDataSource.setJdbcUrl(required("jdbc-url"));
				writeDataSource.setUsername(required("username"));
				writeDataSource.setPassword(required("password"));
				JdbcTemplate jdbc = new JdbcTemplate(writeDataSource);
				String measured=ServingDatabaseIdentity.measure(jdbc);
				ServingDatabaseIdentity.requireMatches(identity.servingDatabaseIdentity(),measured);
				ServingDatabaseIdentity.requirePublicNamespace(jdbc);
				new ProdSchemaGuard(jdbc).verify();
				var request = new AgentApplyRequest(required("approval-digest"),UUID.fromString(required("operation-id")),measured);
				ServingWriter writer = new ServingWriter(jdbc); // Not exported as a bean or HTTP endpoint.
				for (Long proposalId : gate.begin(request)) {
					scope.assertHeld();
					// Any conflict/hold/UNKNOWN interrupts the loop. Existing proposal/apply_log persist the outcome.
					proposals.approveExactManifest(proposalId,request,writer);
					scope.assertHeld();
				}
			}
		}
	}

	private String required(String key) {
		String value = environment.getProperty("dataops.agent.apply."+key);
		if (value == null || value.isBlank()) throw new AgentApplyHold("MANIFEST_RUNNER_CONFIGURATION_REQUIRED");
		return value;
	}
}
