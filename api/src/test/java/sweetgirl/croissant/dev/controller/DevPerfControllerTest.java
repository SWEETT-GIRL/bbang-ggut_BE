package sweetgirl.croissant.dev.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.dev.dto.response.DevEndpointStatsListResponse;
import sweetgirl.croissant.dev.dto.response.DevRequestLogListResponse;
import sweetgirl.croissant.dev.service.DevPerfService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

/** GET /dev/** 는 permitAll 이라 이 컨트롤러의 키 검사가 유일한 문이다 — 프로파일별로 문이 닫히는지 본다. */
@ExtendWith(MockitoExtension.class)
class DevPerfControllerTest {

	private static final String KEY_HEADER = "X-Perf-Dashboard-Key";

	private MockMvc mockMvc;

	@Mock
	private DevPerfService devPerfService;

	@Mock
	private Environment environment;

	@InjectMocks
	private DevPerfController devPerfController;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders
			.standaloneSetup(devPerfController)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	private void givenProfile(String profile) {
		given(environment.getActiveProfiles()).willReturn(new String[] {profile});
	}

	private void givenDashboard(boolean enabled, String accessKey) {
		ReflectionTestUtils.setField(devPerfController, "perfDashboardEnabled", enabled);
		ReflectionTestUtils.setField(devPerfController, "perfDashboardAccessKey", accessKey);
	}

	private void givenLogs() {
		given(devPerfService.getRequestLogs(10, 50, "slow", null))
			.willReturn(new DevRequestLogListResponse(0, 0, null, null, List.of()));
	}

	@Test
	@DisplayName("local 은 키 없이 본다")
	void local_noKey_ok() throws Exception {
		givenProfile("local");
		givenLogs();

		mockMvc.perform(get("/dev/perf/request-logs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.count").value(0));
	}

	@Test
	@DisplayName("staging 은 키 없이 보면 403 — 요청 로그를 비로그인에 열어두지 않는다")
	void staging_noKey_forbidden() throws Exception {
		givenProfile("staging");
		givenDashboard(true, "secret");

		mockMvc.perform(get("/dev/perf/request-logs"))
			.andExpect(status().isForbidden());
		then(devPerfService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("staging 은 키가 틀리면 403")
	void staging_wrongKey_forbidden() throws Exception {
		givenProfile("staging");
		givenDashboard(true, "secret");

		mockMvc.perform(get("/dev/perf/request-logs").header(KEY_HEADER, "wrong"))
			.andExpect(status().isForbidden());
		then(devPerfService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("staging 은 키가 맞으면 본다")
	void staging_key_ok() throws Exception {
		givenProfile("staging");
		givenDashboard(true, "secret");
		givenLogs();

		mockMvc.perform(get("/dev/perf/request-logs").header(KEY_HEADER, "secret"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("대시보드가 꺼져 있거나 키가 비어 있으면 키를 보내도 403 — 그 환경은 닫힌다")
	void staging_dashboardDisabledOrKeyMissing_forbidden() throws Exception {
		givenProfile("staging");
		givenDashboard(false, "secret");
		mockMvc.perform(get("/dev/perf/request-logs").header(KEY_HEADER, "secret"))
			.andExpect(status().isForbidden());

		givenDashboard(true, "");
		mockMvc.perform(get("/dev/perf/request-logs").header(KEY_HEADER, ""))
			.andExpect(status().isForbidden());
		then(devPerfService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("endpoint-stats 도 같은 문 — local 은 키 없이, 기본 창 60분·limit 20 으로 서비스를 부른다")
	void local_endpointStats_ok() throws Exception {
		givenProfile("local");
		given(devPerfService.getEndpointStats(60, null, 20))
			.willReturn(new DevEndpointStatsListResponse(60, 0, List.of()));

		mockMvc.perform(get("/dev/perf/endpoint-stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.sinceMinutes").value(60))
			.andExpect(jsonPath("$.data.totalRequests").value(0));
	}

	@Test
	@DisplayName("endpoint-stats 는 staging 에서 키가 없으면 403")
	void staging_endpointStats_noKey_forbidden() throws Exception {
		givenProfile("staging");
		givenDashboard(true, "secret");

		mockMvc.perform(get("/dev/perf/endpoint-stats").param("sinceMinutes", "1440"))
			.andExpect(status().isForbidden());
		then(devPerfService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("prod 도 그대로 키가 있어야 한다")
	void prod_noKey_forbidden() throws Exception {
		givenProfile("prod");
		givenDashboard(true, "secret");

		mockMvc.perform(get("/dev/perf/request-logs"))
			.andExpect(status().isForbidden());
	}
}
