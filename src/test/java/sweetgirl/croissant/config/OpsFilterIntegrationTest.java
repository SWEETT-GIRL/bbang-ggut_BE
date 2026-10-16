package sweetgirl.croissant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.global.alert.SlackServerErrorAlerter;

/** 운영 스위치를 켠 채로 필터들이 실제 체인에 붙는지 본다(기본 테스트 설정에서는 꺼져 있다). */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:ops-filter-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
	"ops.rate-limit.enabled=true",
	"ops.rate-limit.auth-refresh=1",
	"ops.alert.server-error.enabled=true",
	"shedlock.enabled=true",
	"spring.sql.init.mode=always",
	"spring.sql.init.schema-locations=classpath:db/shedlock-h2.sql"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OpsFilterIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ApplicationContext applicationContext;

	@MockitoBean
	private S3Service s3Service;

	@Test
	@DisplayName("토큰 갱신을 문턱 넘게 부르면 Security 앞에서 429 · COMMON429")
	void rateLimit_blocksBeforeController() throws Exception {
		mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}")
			.header("X-Real-IP", "10.0.0.1"))
			.andExpect(status().isBadRequest());

		mockMvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}")
			.header("X-Real-IP", "10.0.0.1"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.code").value("COMMON429"));
	}

	@Test
	@DisplayName("앞단에서 끝난 응답에도 요청 id 와 서버 시각 헤더가 붙는다")
	void responseHeaders_presentOnEveryResponse() throws Exception {
		mockMvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}")
			.header("X-Request-Id", "it-req-1"))
			.andExpect(header().string("X-Request-Id", "it-req-1"))
			.andExpect(header().string("X-Server-Time", org.hamcrest.Matchers.matchesPattern("\\d{13}")));
	}

	@Test
	@DisplayName("ShedLock 을 켜도 스케줄 빈 프록시와 함께 컨텍스트가 뜬다")
	void schedulerLock_registersLockProvider() {
		assertThat(applicationContext.getBeansOfType(net.javacrumbs.shedlock.core.LockProvider.class)).hasSize(1);
	}

	@Test
	@DisplayName("알림 스위치를 켜면 5xx 알림 빈이 뜬다")
	void serverErrorAlerter_isRegisteredWhenEnabled() {
		assertThat(applicationContext.getBeansOfType(SlackServerErrorAlerter.class)).hasSize(1);
	}
}
