package sweetgirl.croissant.global.logging.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.global.logging.LogEventType;

class RequestLoggingFilterTest {

	@Test
	@DisplayName("성공한 가게 상세·메뉴 GET 은 STORE_VIEW, 구독 POST 는 ALARM_CLICK, 그 외는 없음")
	void resolveEventType_userPaths() {
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/stores/1090", 200))
			.isEqualTo(LogEventType.STORE_VIEW);
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/stores/1090/menus", 200))
			.isEqualTo(LogEventType.STORE_VIEW);
		assertThat(RequestLoggingFilter.resolveEventType("POST", "/stores/1090/subscribe", 200))
			.isEqualTo(LogEventType.ALARM_CLICK);
		assertThat(RequestLoggingFilter.resolveEventType("POST", "/stores/1090/save", 200)).isNull();
	}

	@Test
	@DisplayName("관리자 API · 코스 안 경로 · 실패 응답은 URL 에 /stores/{id} 가 있어도 STORE_VIEW 가 아니다")
	void resolveEventType_notAView() {
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/api/admin/stores/2117/naver-enrich-item", 200))
			.isNull();
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/admin/images/stores/5", 200)).isNull();
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/courses/12/stores/1090/records", 200)).isNull();
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/stores/1090", 404)).isNull();
		assertThat(RequestLoggingFilter.resolveEventType("GET", "/stores/1090", 500)).isNull();
	}
}
