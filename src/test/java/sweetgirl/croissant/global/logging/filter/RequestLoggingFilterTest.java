package sweetgirl.croissant.global.logging.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.global.logging.LogEventType;

class RequestLoggingFilterTest {

	@Test
	@DisplayName("가게 상세·메뉴 GET 은 STORE_VIEW, 구독 POST 는 ALARM_CLICK, 그 외는 없음")
	void resolveEventType_userPaths() {
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/stores/1090")).isEqualTo(LogEventType.STORE_VIEW);
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/stores/1090/menus"))
			.isEqualTo(LogEventType.STORE_VIEW);
		assertThat(RequestLoggingFilter.resolveEventType("POST", "/stores/1090/subscribe"))
			.isEqualTo(LogEventType.ALARM_CLICK);
		assertThat(RequestLoggingFilter.resolveEventType("POST", "/stores/1090/save")).isNull();
	}

	@Test
	@DisplayName("관리자 API 는 URL 에 /stores/{id} 가 있어도 STORE_VIEW 가 아니다 — dataops 보강 스크립트가 조회로 잡히던 문제")
	void resolveEventType_adminPathsAreNotViews() {
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/api/admin/stores/2117/naver-enrich-item")).isNull();
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/api/admin/stores/70714/menus")).isNull();
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/admin/images/stores/5")).isNull();
	}
}
