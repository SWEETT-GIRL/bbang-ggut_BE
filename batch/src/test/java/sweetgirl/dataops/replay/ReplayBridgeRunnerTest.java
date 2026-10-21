package sweetgirl.dataops.replay;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ReplayBridgeRunnerTest {
    @TempDir Path temporary;
    @Test void failure_projection_exposes_only_bounded_domain_identifiers() {
        assertThat(ReplayBridgeRunner.failureCode(new IllegalStateException("MEASURED_RUNTIME_CHANGED"))).isEqualTo("MEASURED_RUNTIME_CHANGED");
        assertThat(ReplayBridgeRunner.failureCode(new IllegalStateException("password=secret raw payload"))).isEqualTo("REPLAY_EXECUTION_FAILED");
        assertThat(ReplayBridgeRunner.failureCode(new IllegalArgumentException("DATABASE_URL"))).isEqualTo("REPLAY_EXECUTION_FAILED");
    }
    @Test void receipt_is_atomic_private_and_rejects_symlink_destinations() throws Exception {
        Path parent=temporary.toRealPath(),destination=parent.resolve("receipt.json");
        ReplayBridgeRunner.writeReceipt(destination,"{\"outcome\":\"UNKNOWN\"}\n");
        ReplayBridgeRunner.writeReceipt(destination,"{\"outcome\":\"DONE\"}\n");
        assertThat(Files.readString(destination)).contains("DONE").doesNotContain("UNKNOWN");
        assertThat(Files.getPosixFilePermissions(destination)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
        Path link=parent.resolve("link.json");Files.createSymbolicLink(link,destination);
        assertThatThrownBy(()->ReplayBridgeRunner.writeReceipt(link,"forged")).hasMessage("RECEIPT_PATH_UNSAFE");
        assertThat(Files.readString(destination)).contains("DONE");
        try(var files=Files.list(parent)){assertThat(files.map(path->path.getFileName().toString()).toList()).doesNotContain(".replay-receipt-");}
    }
    @Test void readonly_guard_runs_before_context_refresh_and_rejects_imported_mutation_configuration() {
        ReplayBridgeMain.assertReadonly(environment());
        for(var entry:Map.of("spring.flyway.enabled","true","dataops.serving.write-enabled","true",
            "dataops.agent.apply.enabled","true","spring.jpa.hibernate.ddl-auto","update","spring.sql.init.mode","always",
            "spring.main.web-application-type","servlet","dataops.reconcile.mode","AUTO_APPLY",
            "dataops.agent.execution.enabled","false","dataops.agent.replay.enabled","false").entrySet())
            assertThatThrownBy(()->ReplayBridgeMain.assertReadonly(environment().withProperty(entry.getKey(),entry.getValue())))
                .as(entry.getKey()).hasMessage("REPLAY_RUNNER_MUST_BE_NONWEB_AND_READONLY");
    }
    MockEnvironment environment(){return new MockEnvironment().withProperty("spring.main.web-application-type","none")
        .withProperty("spring.flyway.enabled","false").withProperty("dataops.agent.execution.enabled","true")
        .withProperty("dataops.agent.replay.enabled","true").withProperty("dataops.reconcile.mode","PROPOSAL_ONLY");}
}
