package sweetgirl.dataops.config.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import sweetgirl.dataops.DataopsApplication;

/**
 * WAS 와 한 레포가 되며 Spring Security 가 클래스패스에 들어왔다. 기본 Basic 인증이 아니라 API 키로만 막히는지 본다.
 */
@SpringBootTest(properties = {
	"spring.config.name=" + DataopsApplication.CONFIG_NAME,
	"dataops.api-key=test-dataops-key"
})
@AutoConfigureMockMvc
class DataopsSecurityIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("키가 맞으면 dataops API 가 응답한다")
	void apiRespondsWithKey() throws Exception {
		mockMvc.perform(get("/api/apply-logs").header(DataopsApiKeyFilter.HEADER, "test-dataops-key"))
			.andExpect(status().isOk());
	}

	@Test
	@DisplayName("키가 없으면 401 — Basic 인증 화면이나 403 이 아니다")
	void apiRejectsWithoutKey() throws Exception {
		mockMvc.perform(get("/api/apply-logs"))
			.andExpect(status().isUnauthorized());
	}
}
