package sweetgirl.croissant.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SensitiveUrlMasker")
class SensitiveUrlMaskerTest {

	@Test
	@DisplayName("초대·공유 토큰 경로 세그먼트만 가리고, 뒤에 붙는 /accept 와 다른 경로는 그대로다")
	void maskPath_masksTokenSegmentsOnly() {
		assertThat(SensitiveUrlMasker.maskPath("/courses/invites/k9J_abc-XYZ")).isEqualTo("/courses/invites/***");
		assertThat(SensitiveUrlMasker.maskPath("/courses/invites/k9J_abc/accept"))
			.isEqualTo("/courses/invites/***/accept");
		assertThat(SensitiveUrlMasker.maskPath("/courses/by-share/Qm7xyz")).isEqualTo("/courses/by-share/***");
		assertThat(SensitiveUrlMasker.maskPath("/courses/12")).isEqualTo("/courses/12");
		assertThat(SensitiveUrlMasker.maskPath("/courses/12/invites")).isEqualTo("/courses/12/invites");
		assertThat(SensitiveUrlMasker.maskPath(null)).isNull();
	}

	@Test
	@DisplayName("쿼리의 t= 값만 가리고 다른 파라미터는 그대로다 — 맨 앞·중간·끝 어디에 있든")
	void maskQuery_masksOnlyShareTokenParam() {
		assertThat(SensitiveUrlMasker.maskQuery("t=Qm7xyz")).isEqualTo("t=***");
		assertThat(SensitiveUrlMasker.maskQuery("lat=37.5&t=Qm7xyz&lng=127.0")).isEqualTo("lat=37.5&t=***&lng=127.0");
		assertThat(SensitiveUrlMasker.maskQuery("lat=37.5&t=Qm7xyz")).isEqualTo("lat=37.5&t=***");
		assertThat(SensitiveUrlMasker.maskQuery("sort=recent&limit=20")).isEqualTo("sort=recent&limit=20");
		assertThat(SensitiveUrlMasker.maskQuery(null)).isNull();
	}

	@Test
	@DisplayName("CommonsRequestLoggingFilter 메시지처럼 URI·쿼리·클라이언트 정보가 섞인 문자열도 토큰만 가린다")
	void maskUrl_masksInsideCombinedMessage() {
		String message = "Before request [GET /courses/12?t=Qm7xyz;client=10.0.0.1] and /courses/invites/k9J_abc";

		assertThat(SensitiveUrlMasker.maskUrl(message))
			.isEqualTo("Before request [GET /courses/12?t=***;client=10.0.0.1] and /courses/invites/***");
	}
}
