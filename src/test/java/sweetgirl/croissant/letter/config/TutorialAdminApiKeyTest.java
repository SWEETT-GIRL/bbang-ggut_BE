package sweetgirl.croissant.letter.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TutorialAdminApiKeyTest {
	@ParameterizedTest
	@ValueSource(strings = {"/api/admin/tutorial-templates", "/api/admin/tutorial-templates/upload-url",
		"/api/admin/tutorial-templates/code/publish", "/api/admin/tutorial-templates/stop"})
	void missingConfiguredKeyFailsClosedEvenOnLocal(String path) throws Exception {
		var response = new MockHttpServletResponse();
		new AdminApiKeyFilter("", true).doFilter(new MockHttpServletRequest("POST", path), response,
			(request, result) -> {
				throw new AssertionError("키가 없으면 통과하지 않아야 한다");
			});
		assertThat(response.getStatus()).isEqualTo(503);
	}
}
