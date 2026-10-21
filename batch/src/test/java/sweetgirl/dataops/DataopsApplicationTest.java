package sweetgirl.dataops;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 컨텍스트 로드 스모크 — 엔티티 매핑(H2 create-drop)·JPQL 파싱·빈 구성이 깨지면 여기서 잡힌다 */
@SpringBootTest(properties = "spring.config.name=" + DataopsApplication.CONFIG_NAME)
class DataopsApplicationTest {

	@Test
	void contextLoads() {}
}
