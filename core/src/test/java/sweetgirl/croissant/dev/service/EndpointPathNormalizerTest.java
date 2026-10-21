package sweetgirl.croissant.dev.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EndpointPathNormalizerTest {

	@Test
	@DisplayName("숫자 세그먼트만 {id} 로 — 가운데·끝 모두, 숫자가 섞인 이름(v1·autocomplete)은 그대로")
	void foldsNumericSegments() {
		assertThat(EndpointPathNormalizer.normalize("/stores/123")).isEqualTo("/stores/{id}");
		assertThat(EndpointPathNormalizer.normalize("/courses/45/stores/678/records"))
			.isEqualTo("/courses/{id}/stores/{id}/records");
		assertThat(EndpointPathNormalizer.normalize("/api/v1/stores/autocomplete"))
			.isEqualTo("/api/v1/stores/autocomplete");
	}

	@Test
	@DisplayName("초대·공유 토큰은 가린 값으로 모이고, 빈 경로는 (none)")
	void masksTokensAndHandlesBlank() {
		assertThat(EndpointPathNormalizer.normalize("/courses/invites/AbC-123_xyz/accept"))
			.isEqualTo("/courses/invites/***/accept");
		assertThat(EndpointPathNormalizer.normalize(null)).isEqualTo("(none)");
		assertThat(EndpointPathNormalizer.normalize("  ")).isEqualTo("(none)");
	}
}
