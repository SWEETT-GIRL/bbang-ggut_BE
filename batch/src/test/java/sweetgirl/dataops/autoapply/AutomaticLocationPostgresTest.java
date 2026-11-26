package sweetgirl.dataops.autoapply;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import sweetgirl.dataops.serving.client.ServingWriter;

@EnabledIfEnvironmentVariable(named="DATAOPS_AUTO_APPLY_POSTGIS_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/.*")
class AutomaticLocationPostgresTest {
	@Test void address_region_and_coordinates_change_together_only_with_matching_old_location() {
		String base=System.getenv("DATAOPS_AUTO_APPLY_POSTGIS_URL");
		var admin=new JdbcTemplate(new DriverManagerDataSource(base,"postgres",""));
		admin.execute("CREATE EXTENSION IF NOT EXISTS postgis");
		String schema="location_"+UUID.randomUUID().toString().replace("-","");
		admin.execute("CREATE SCHEMA "+schema);
		try {
			var jdbc=new JdbcTemplate(new DriverManagerDataSource(base+"?currentSchema="+schema+",public","postgres",""));
			jdbc.execute("CREATE TABLE store(id bigint primary key,naver_place_id text,status text,address text,location geography(Point,4326),region_1st text,region_2nd text,region_3rd text,updated_at timestamp)");
			jdbc.execute("INSERT INTO store(id,naver_place_id,status,address,location) VALUES(1,'123','ACTIVE','이전',ST_SetSRID(ST_MakePoint(127,37),4326)::geography)");
			var writer=new ServingWriter(jdbc);
			assertThat(writer.updateObservedStoreAddress(1,"wrong","이전",127,37,"새주소",127.001,37,"서울","구","동")).isZero();
			assertThat(writer.updateObservedStoreAddress(1,"123","이전",128,37,"새주소",127.001,37,"서울","구","동")).isZero();
			assertThat(writer.updateObservedStoreAddress(1,"123","이전",127,37,"새주소",127.001,37,"서울","구","동")).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT address||':'||region_3rd FROM store",String.class)).isEqualTo("새주소:동");
			assertThat(writer.updateObservedStoreAddress(1,"123","이전",127,37,"다시",127.002,37,"서울","구","동")).isZero();
		} finally {admin.execute("DROP SCHEMA "+schema+" CASCADE");}
	}
}
