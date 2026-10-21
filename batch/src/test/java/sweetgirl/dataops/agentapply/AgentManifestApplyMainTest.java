package sweetgirl.dataops.agentapply;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AgentManifestApplyMainTest {
    private MockEnvironment safe(){return new MockEnvironment()
        .withProperty("spring.main.web-application-type","none").withProperty("spring.flyway.enabled","false")
        .withProperty("dataops.serving.write-enabled","false").withProperty("dataops.reconcile.mode","PROPOSAL_ONLY")
        .withProperty("dataops.agent.execution.enabled","true").withProperty("dataops.agent.apply.enabled","true")
        .withProperty("dataops.agent.replay.enabled","false").withProperty("spring.sql.init.mode","never")
        .withProperty("spring.jpa.hibernate.ddl-auto","validate");}
    @Test void safeDefaultsAreAccepted(){assertDoesNotThrow(()->AgentManifestApplyMain.assertScoped(safe()));}
    @Test void unsafeImportedSettingsFailBeforeRefresh() {
        for(String[] pair:new String[][]{{"spring.main.web-application-type","servlet"},{"spring.flyway.enabled","true"},
            {"dataops.serving.write-enabled","true"},{"dataops.reconcile.mode","AUTO_APPLY"},{"dataops.agent.execution.enabled","false"},
            {"dataops.agent.apply.enabled","false"},{"dataops.agent.replay.enabled","true"},{"spring.sql.init.mode","always"},
            {"spring.jpa.hibernate.ddl-auto","update"}})
            assertThrows(AgentApplyHold.class,()->AgentManifestApplyMain.assertScoped(safe().withProperty(pair[0],pair[1])),pair[0]);
    }
}
