package sweetgirl.dataops.runtimeattestation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.ObjectMapper;

class RuntimeMeasurementTest {
    @TempDir Path temporary;
    @Test void artifact_measurement_reads_bytes_and_rejects_exploded_or_symbolic_sources() throws Exception {
        Path jar=temporary.resolve("service.jar");Files.writeString(jar,"first immutable artifact");
        assertThat(RuntimeMeasurement.artifactDigest(jar)).isEqualTo(ResponseDigests.sha256("first immutable artifact"));
        Files.writeString(jar,"different artifact");
        assertThat(RuntimeMeasurement.artifactDigest(jar)).isEqualTo(ResponseDigests.sha256("different artifact"));
        Path link=temporary.resolve("pointer.jar");Files.createSymbolicLink(link,jar);
        assertThatThrownBy(()->RuntimeMeasurement.artifactDigest(link)).hasMessage("EXECUTING_JAR_REQUIRED");
        assertThatThrownBy(()->RuntimeMeasurement.artifactDigest(temporary)).hasMessage("EXECUTING_JAR_REQUIRED");
        assertThatThrownBy(()->RuntimeMeasurement.artifactDigest(RuntimeMeasurement.executingArtifact())).hasMessage("EXECUTING_JAR_REQUIRED");
        assertThat(RuntimeMeasurement.semanticContractDigest()).matches("[0-9a-f]{64}")
            .isEqualTo(RuntimeMeasurement.semanticContractDigest());
    }
    @Test void missing_actual_serving_connection_cannot_be_replaced_with_a_caller_database_digest() {
        ObjectMapper mapper=new ObjectMapper();
        var diagnostic=new ResponseDiagnostics(mapper,new NaverPlaceParser(),new BusinessHoursParser(),new MenuEvidenceParser(mapper));
        var measurement=new RuntimeMeasurement(mock(JdbcTemplate.class),()->null,diagnostic,()->"a".repeat(64),()->temporary,mapper);
        assertThatThrownBy(measurement::measure).hasMessage("SERVING_MEASUREMENT_UNAVAILABLE");
    }
}
