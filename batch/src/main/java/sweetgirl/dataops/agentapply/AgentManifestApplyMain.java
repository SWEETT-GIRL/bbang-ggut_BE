package sweetgirl.dataops.agentapply;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

import sweetgirl.dataops.DataopsApplication;

/** Trusted host entry point; no attestor is fabricated and no approval is created here. */
public final class AgentManifestApplyMain {
	private AgentManifestApplyMain() {}
	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(DataopsApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setDefaultProperties(DataopsApplication.defaultProperties(Map.of("spring.main.web-application-type","none","dataops.agent.apply.enabled","true",
			"dataops.serving.write-enabled","false","dataops.reconcile.mode","PROPOSAL_ONLY", "spring.flyway.enabled","false",
            "spring.sql.init.mode","never","dataops.agent.replay.enabled","false","dataops.agent.execution.enabled","true")));
        application.addInitializers(context -> {
            if (context instanceof WebApplicationContext) throw new AgentApplyHold("MANIFEST_RUNNER_MUST_BE_NONWEB_AND_SCOPED");
            assertScoped(context.getEnvironment());
        });
		try (var context = application.run(args)) { /* Runner settles or fails before returning. */ }
	}
    static void assertScoped(Environment environment) {
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type",""))
            || environment.getProperty("spring.flyway.enabled",Boolean.class,true)
            || environment.getProperty("dataops.serving.write-enabled",Boolean.class,false)
            || environment.getProperty("dataops.agent.replay.enabled",Boolean.class,false)
            || !environment.getProperty("dataops.agent.apply.enabled",Boolean.class,false)
            || !environment.getProperty("dataops.agent.execution.enabled",Boolean.class,false)
            || !"PROPOSAL_ONLY".equals(environment.getProperty("dataops.reconcile.mode"))
            || !java.util.Set.of("none","validate").contains(environment.getProperty("spring.jpa.hibernate.ddl-auto","none"))
            || !"never".equals(environment.getProperty("spring.sql.init.mode","never")))
            throw new AgentApplyHold("MANIFEST_RUNNER_MUST_BE_NONWEB_AND_SCOPED");
    }
}
