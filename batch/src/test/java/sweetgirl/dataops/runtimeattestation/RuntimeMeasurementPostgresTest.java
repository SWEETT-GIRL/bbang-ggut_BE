package sweetgirl.dataops.runtimeattestation;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import sweetgirl.dataops.agentapply.ServingDatabaseIdentity;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.service.ResponseDiagnostics;
import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="DATAOPS_BRIDGE_TEST_JDBC_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/agent_bridge_test")
class RuntimeMeasurementPostgresTest {
    @TempDir Path temporary;
    @Test void actual_cluster_database_oid_and_name_are_bound_in_a_directional_composite() throws Exception {
        String url=System.getenv("DATAOPS_BRIDGE_TEST_JDBC_URL");
        var dataops=new JdbcTemplate(new DriverManagerDataSource(url,"postgres",""));
        var serving=new JdbcTemplate(new DriverManagerDataSource(url.replace("/agent_bridge_test","/postgres"),"postgres",""));
        var mapper=new ObjectMapper();
        var diagnostic=new ResponseDiagnostics(mapper,new NaverPlaceParser(),new BusinessHoursParser(),new MenuEvidenceParser(mapper));
        Path artifact=temporary.resolve("fixture.jar");Files.writeString(artifact,"fixture bytes");
        var value=new RuntimeMeasurement(dataops,()->serving,diagnostic,()->"a".repeat(64),()->artifact,mapper).measure();
        String dataopsId=ServingDatabaseIdentity.measure(dataops),servingId=ServingDatabaseIdentity.measure(serving);
        assertThat(dataopsId).isNotEqualTo(servingId);
        assertThat(value.dataopsDatabaseIdentity()).isEqualTo(dataopsId);
        assertThat(value.servingDatabaseIdentity()).isEqualTo(servingId);
        assertThat(value.databaseIdentity()).isEqualTo(ResponseDigests.sha256(ResponseDigests.canonical(
            mapper.valueToTree(Map.of("dataops",dataopsId,"serving",servingId)),mapper)));
        var reversed=new RuntimeMeasurement(serving,()->dataops,diagnostic,()->"a".repeat(64),()->artifact,mapper).measure();
        assertThat(reversed.databaseIdentity()).isNotEqualTo(value.databaseIdentity());
    }
}
