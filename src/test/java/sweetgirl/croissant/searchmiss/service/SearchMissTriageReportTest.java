package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Field;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

class SearchMissTriageReportTest {

	private static final Duration ELAPSED = Duration.ofSeconds(252);

	private static SearchMissTriageItem item(String keyword, long count, SearchMissVerdict verdict,
			Map<String, Object> evidence) {
		return new SearchMissTriageItem(keyword, count, new TriageDecision(verdict, null, null, evidence));
	}

	private static String fieldValue(OperationalNotificationEvent report, String namePrefix) {
		return report.fields().stream()
			.filter(field -> field.name().startsWith(namePrefix))
			.map(Field::value)
			.findFirst()
			.orElseThrow();
	}

	private static SearchMissTriageBatchResult sampleResult() {
		return new SearchMissTriageBatchResult(5, List.of(
			item("ㅋㅋㅋ", 1, SearchMissVerdict.JUNK, Map.of()),
			item("런베뮤", 7, SearchMissVerdict.ALIAS_RESOLVED,
				Map.of("matchedStoreName", "런던베이글뮤지엄 안국점", "aliasStoreIds", List.of(1L, 2L, 3L))),
			item("새빵공방", 4, SearchMissVerdict.NEW_STORE_CANDIDATE, Map.of("newStoreCandidates",
				List.of(Map.of("name", "새빵공방", "category", "베이커리", "address", "서울 마포구")))),
			item("빵지순례", 9, SearchMissVerdict.UNRESOLVED, Map.of("reason", "근거 부족"))),
			List.of(new SearchMissTriageFailure("어니온", "네이버 검색 실패: 503")), null);
	}

	@Test
	@DisplayName("완료 리포트는 실패 검색어 채널로, 모드·건수·소요 시간을 요약한다")
	void completed_summary() {
		OperationalNotificationEvent report = SearchMissTriageReport.completed(sampleResult(), false, ELAPSED);

		assertThat(report.channel()).isEqualTo(Channel.SEARCH_MISS);
		assertThat(report.title()).contains("완료");
		assertThat(report.description()).contains("shadow").contains("꺼낸 5건").contains("판정 4건")
			.contains("실패 1건").contains("4분 12초");
		// 실패가 있으면 경고 색
		assertThat(report.color()).isEqualTo(SearchMissTriageReport.WARNING_COLOR);
	}

	@Test
	@DisplayName("판정별 건수에 자동으로 닫힌(또는 닫힐 예정인) 상태와 사람이 볼 것을 구분해 적는다")
	void completed_verdictCounts() {
		String shadow = fieldValue(SearchMissTriageReport.completed(sampleResult(), false, ELAPSED), "판정별 건수");
		String applied = fieldValue(SearchMissTriageReport.completed(sampleResult(), true, ELAPSED), "판정별 건수");

		assertThat(shadow).contains("JUNK 1건 → IGNORED 예정 (shadow, 미적용)")
			.contains("NEW_STORE_CANDIDATE 1건 → 사람이 확인");
		assertThat(applied).contains("ALIAS_RESOLVED 1건 → " + SearchMissStatus.ALREADY_EXISTS.name())
			.doesNotContain("미적용");
	}

	@Test
	@DisplayName("무엇을 어떻게 처리했는지 검색어별로 보여 준다")
	void completed_details() {
		OperationalNotificationEvent report = SearchMissTriageReport.completed(sampleResult(), true, ELAPSED);

		assertThat(fieldValue(report, "별칭 추가")).contains("런베뮤 (7회) → 런던베이글뮤지엄 안국점 (지점 3곳)");
		assertThat(fieldValue(report, "👀 신규 가게 후보")).contains("새빵공방 (4회) — 네이버: 새빵공방 · 베이커리 · 서울 마포구");
		assertThat(fieldValue(report, "👀 판단 보류")).contains("빵지순례 (9회) — 근거 부족");
		assertThat(fieldValue(report, "❌ 실패")).contains("어니온 — 네이버 검색 실패: 503");
	}

	@Test
	@DisplayName("목록은 많이 찾힌 순으로 앞쪽만 싣고 나머지는 건수로 줄인다")
	void completed_truncatesLongLists() {
		List<SearchMissTriageItem> items = new ArrayList<>();
		for (int index = 1; index <= 8; index++) {
			items.add(item("보류" + index, index, SearchMissVerdict.UNRESOLVED, Map.of("reason", "근거 부족")));
		}

		String unresolved = fieldValue(SearchMissTriageReport.completed(
			new SearchMissTriageBatchResult(8, items, List.of(), null), true, ELAPSED), "👀 판단 보류");

		assertThat(unresolved).startsWith("• 보류8 (8회)").contains("… 외 3건").doesNotContain("보류1 ");
	}

	@Test
	@DisplayName("한도 초과로 멈췄으면 제목에 사유를, 요약에 못 본 건수를 적는다")
	void completed_stopped() {
		SearchMissTriageBatchResult result = new SearchMissTriageBatchResult(10,
			List.of(item("ㅋㅋㅋ", 1, SearchMissVerdict.JUNK, Map.of())), List.of(), "네이버 검색 API 한도 초과");

		OperationalNotificationEvent report = SearchMissTriageReport.completed(result, true, ELAPSED);

		assertThat(report.title()).contains("중단").contains("네이버 검색 API 한도 초과");
		assertThat(report.description()).contains("못 본 9건(내일 다시)");
	}

	@Test
	@DisplayName("판정할 행이 없으면 그렇다고 적고 목록은 비운다")
	void completed_empty() {
		OperationalNotificationEvent report = SearchMissTriageReport.completed(
			new SearchMissTriageBatchResult(0, List.of(), List.of(), null), false, Duration.ofSeconds(1));

		assertThat(report.description()).contains("판정할 검색어가 없었습니다");
		assertThat(report.fields()).isEmpty();
		assertThat(report.color()).isEqualTo(SearchMissTriageReport.SUCCESS_COLOR);
	}

	@Test
	@DisplayName("배치가 예외로 끝나면 실패 리포트에 오류를 담는다")
	void failed() {
		OperationalNotificationEvent report = SearchMissTriageReport.failed(new IllegalStateException("DB 끊김"),
			true, ELAPSED);

		assertThat(report.title()).contains("실패");
		assertThat(report.color()).isEqualTo(SearchMissTriageReport.FAILURE_COLOR);
		assertThat(report.fields()).singleElement()
			.satisfies(field -> assertThat(field.value()).isEqualTo("IllegalStateException: DB 끊김"));
	}

	@Test
	@DisplayName("소요 시간은 1분 미만이면 초만, 넘으면 분·초로 적는다")
	void format() {
		assertThat(SearchMissTriageReport.format(Duration.ofSeconds(42))).isEqualTo("42초");
		assertThat(SearchMissTriageReport.format(Duration.ofSeconds(252))).isEqualTo("4분 12초");
	}
}
