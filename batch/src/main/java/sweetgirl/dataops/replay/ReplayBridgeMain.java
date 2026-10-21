package sweetgirl.dataops.replay;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.Environment;
import org.springframework.web.context.WebApplicationContext;

import sweetgirl.dataops.DataopsApplication;

/** Launch the exact immutable Boot JAR with PropertiesLauncher and this loader.main; no web listener. */
public final class ReplayBridgeMain {
	private ReplayBridgeMain() {}
	public static void main(String[] args) {
		var application=new SpringApplication(DataopsApplication.class);
		application.setWebApplicationType(WebApplicationType.NONE);
		application.setDefaultProperties(DataopsApplication.defaultProperties(Map.of("spring.main.web-application-type","none","spring.flyway.enabled","false",
			"dataops.agent.execution.enabled","true","dataops.agent.replay.enabled","true",
			"dataops.serving.write-enabled","false","dataops.agent.apply.enabled","false","dataops.reconcile.mode","PROPOSAL_ONLY",
			"spring.sql.init.mode","never")));
		// Initializers run before refresh: an imported .env cannot enable migrations or a writer first.
		application.addInitializers(context->{
			if(context instanceof WebApplicationContext)throw new IllegalStateException("REPLAY_RUNNER_MUST_BE_NONWEB_AND_READONLY");
			assertReadonly(context.getEnvironment());
		});
		try(var context=application.run(args)) { /* Receipt is persisted before returning. */ }
	}
	static void assertReadonly(Environment environment) {
		if(!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type",""))
			|| environment.getProperty("spring.flyway.enabled",Boolean.class,true)
			|| environment.getProperty("dataops.serving.write-enabled",Boolean.class,false)
			|| environment.getProperty("dataops.agent.apply.enabled",Boolean.class,false)
			|| !environment.getProperty("dataops.agent.execution.enabled",Boolean.class,false)
			|| !environment.getProperty("dataops.agent.replay.enabled",Boolean.class,false)
			|| !"PROPOSAL_ONLY".equals(environment.getProperty("dataops.reconcile.mode"))
			|| !java.util.Set.of("none","validate").contains(environment.getProperty("spring.jpa.hibernate.ddl-auto","none"))
			|| !"never".equals(environment.getProperty("spring.sql.init.mode","never")))
			throw new IllegalStateException("REPLAY_RUNNER_MUST_BE_NONWEB_AND_READONLY");
	}
}
