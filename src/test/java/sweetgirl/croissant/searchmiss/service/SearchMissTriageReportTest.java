package sweetgirl.croissant.searchmiss.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.external.slack.dto.SlackMessage;
import sweetgirl.croissant.external.slack.dto.SlackMessage.Field;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

class SearchMissTriageReportTest {

	private static final Duration ELAPSED = Duration.ofSeconds(252);
	private static final Instant AT = Instant.parse("2026-10-18T21:00:00Z");
	private static final Set<SearchMissVerdict> SHADOW = EnumSet.noneOf(SearchMissVerdict.class);
	private static final Set<SearchMissVerdict> CORRECTION_ON = EnumSet.of(SearchMissVerdict.CORRECTED);

	private static SearchMissTriageItem item(String keyword, long count, SearchMissVerdict verdict,
			Map<String, Object> evidence) {
		return new SearchMissTriageItem(keyword, count, new TriageDecision(verdict, null, null, evidence));
	}

	private static List<Field> allFields(List<SlackMessage> messages) {
		return messages.stream().flatMap(message -> message.fields().stream()).toList();
	}

	private static String fieldValue(List<SlackMessage> messages, String namePrefix) {
		return allFields(messages).stream()
			.filter(field -> field.name().startsWith(namePrefix))
			.map(Field::value)
			.reduce((left, right) -> left + "\n" + right)
			.orElseThrow();
	}

	private static SearchMissTriageBatchResult sampleResult() {
		return new SearchMissTriageBatchResult(6, List.of(
			item("ㅋㅋㅋ", 1, SearchMissVerdict.JUNK, Map.of("reason", "쓰레기 입력")),
			item("hohaho2514@gmail.com", 7, SearchMissVerdict.JUNK, Map.of("reason", "개인정보(이메일·전화번호)")),
			item("콜버", 9, SearchMissVerdict.CORRECTED,
				Map.of("correctionTarget", "콜드버터베이크샵", "matchedStoreIds", List.of(1L, 2L, 3L))),
			item("새빵공방", 4, SearchMissVerdict.NEW_STORE_CANDIDATE, Map.of("newStoreCandidates",
				List.of(Map.of("name", "새빵공방", "category", "베이커리", "address", "서울 마포구")))),
			item("빵지순례", 9, SearchMissVerdict.UNRESOLVED, Map.of("reason", "근거 부족"))),
			List.of(new SearchMissTriageFailure("어니온", "네이버 검색 실패: 503")), null);
	}

	@Test
	@DisplayName("완료 리포트는 모드·건수·소요 시간을 요약하고, 실패가 있으면 경고 색이다")
	void completed_summary() {
		List<SlackMessage> messages = SearchMissTriageReport.completed(sampleResult(), SHADOW, ELAPSED, AT);

		SlackMessage first = messages.get(0);
		assertThat(first.title()).contains("완료");
		assertThat(first.description()).contains("shadow").contains("꺼낸 6건").contains("판정 5건")
			.contains("실패 1건").contains("4분 12초");
		assertThat(first.color()).isEqualTo(SearchMissTriageReport.WARNING_COLOR);
		assertThat(first.timestamp()).isEqualTo(AT);
	}

	@Test
	@DisplayName("shadow 면 교정은 '예정 — 미적용'으로, 켠 판정은 바뀐 상태로 적는다")
	void completed_labelsByAutoApply() {
		List<SlackMessage> shadow = SearchMissTriageReport.completed(sampleResult(), SHADOW, ELAPSED, AT);
		List<SlackMessage> applied = SearchMissTriageReport.completed(sampleResult(), CORRECTION_ON, ELAPSED, AT);

		assertThat(fieldValue(shadow, "판정별 건수")).contains("CORRECTED 1건 → ALREADY_EXISTS 예정 (미적용)")
			.contains("NEW_STORE_CANDIDATE 1건 → 사람이 확인");
		assertThat(allFields(shadow)).extracting(Field::name).anyMatch(name -> name.startsWith("검색어 교정 예정"));
		assertThat(fieldValue(applied, "판정별 건수")).contains("CORRECTED 1건 → ALREADY_EXISTS\n")
			.contains("JUNK 2건 → IGNORED 예정 (미적용)");
		assertThat(applied.get(0).description()).contains("자동 적용").contains("CORRECTED");
	}

	@Test
	@DisplayName("무엇을 어떻게 처리했는지 검색어별로 보여 주고, 개인정보는 가린다")
	void completed_details() {
		List<SlackMessage> messages = SearchMissTriageReport.completed(sampleResult(), CORRECTION_ON, ELAPSED, AT);

		assertThat(fieldValue(messages, "검색어 교정")).contains("콜버 (9회) → 콜드버터베이크샵 (가게 3곳)");
		assertThat(fieldValue(messages, "👀 신규 가게 후보")).contains("새빵공방 (4회) — 네이버: 새빵공방 · 베이커리 · 서울 마포구");
		assertThat(fieldValue(messages, "👀 판단 보류")).contains("빵지순례 (9회) — 근거 부족");
		assertThat(fieldValue(messages, "쓰레기 입력")).contains("ho***@gmail.com (7회)").doesNotContain("hohaho2514");
		assertThat(fieldValue(messages, "❌ 실패")).contains("어니온 — 네이버 검색 실패: 503");
	}

	@Test
	@DisplayName("목록을 줄이지 않는다 — 칸 한도를 넘으면 '(이어서)' 칸으로 나눠 전부 싣는다")
	void completed_neverTruncates() {
		List<SearchMissTriageItem> items = new ArrayList<>();
		for (int index = 1; index <= 300; index++) {
			items.add(item("보류검색어" + index, index, SearchMissVerdict.UNRESOLVED, Map.of("reason", "근거 부족")));
		}

		List<SlackMessage> messages = SearchMissTriageReport.completed(
			new SearchMissTriageBatchResult(300, items, List.of(), null), SHADOW, ELAPSED, AT);

		String unresolved = fieldValue(messages, "👀 판단 보류");
		assertThat(unresolved).contains("보류검색어300 (300회)").contains("보류검색어1 (1회)").doesNotContain("외");
		assertThat(allFields(messages)).allSatisfy(
			field -> assertThat(field.value().length()).isLessThanOrEqualTo(SearchMissTriageReport.FIELD_VALUE_MAX));
		assertThat(allFields(messages)).extracting(Field::name).anyMatch(name -> name.endsWith("(이어서)"));
	}

	@Test
	@DisplayName("칸이 한 메시지 한도를 넘으면 메시지를 여러 개로 나누고 제목에 (n/N)을 붙인다")
	void paginate_splitsMessages() {
		List<Field> fields = new ArrayList<>();
		for (int index = 0; index < SearchMissTriageReport.FIELDS_PER_MESSAGE * 2 + 1; index++) {
			fields.add(new Field("칸" + index, "값", false));
		}

		List<SlackMessage> messages = SearchMissTriageReport.paginate("제목", "요약", 1, AT, fields);

		assertThat(messages).hasSize(3);
		assertThat(messages.get(0).title()).isEqualTo("제목 (1/3)");
		assertThat(messages.get(0).description()).isEqualTo("요약");
		assertThat(messages.get(2).title()).isEqualTo("제목 (3/3)");
		assertThat(messages.get(2).description()).isEqualTo("이어서 — 3/3");
		assertThat(messages.get(2).fields()).hasSize(1);
	}

	@Test
	@DisplayName("한도 초과로 멈췄으면 제목에 사유를, 요약에 못 본 건수를 적는다")
	void completed_stopped() {
		SearchMissTriageBatchResult result = new SearchMissTriageBatchResult(10,
			List.of(item("ㅋㅋㅋ", 1, SearchMissVerdict.JUNK, Map.of())), List.of(), "네이버 검색 API 한도 초과");

		SlackMessage first = SearchMissTriageReport.completed(result, SHADOW, ELAPSED, AT).get(0);

		assertThat(first.title()).contains("중단").contains("네이버 검색 API 한도 초과");
		assertThat(first.description()).contains("못 본 9건(내일 다시)");
	}

	@Test
	@DisplayName("판정할 행이 없어도 메시지 하나를 만든다 — 리포트가 없으면 안 돈 것이다")
	void completed_empty() {
		List<SlackMessage> messages = SearchMissTriageReport.completed(
			new SearchMissTriageBatchResult(0, List.of(), List.of(), null), SHADOW, Duration.ofSeconds(1), AT);

		assertThat(messages).singleElement().satisfies(message -> {
			assertThat(message.description()).contains("판정할 검색어가 없었습니다");
			assertThat(message.fields()).isEmpty();
			assertThat(message.color()).isEqualTo(SearchMissTriageReport.SUCCESS_COLOR);
		});
	}

	@Test
	@DisplayName("배치가 예외로 끝나면 실패 리포트에 오류를 담는다")
	void failed() {
		List<SlackMessage> messages = SearchMissTriageReport.failed(new IllegalStateException("DB 끊김"),
			CORRECTION_ON, ELAPSED, AT);

		assertThat(messages).singleElement().satisfies(message -> {
			assertThat(message.title()).contains("실패");
			assertThat(message.color()).isEqualTo(SearchMissTriageReport.FAILURE_COLOR);
			assertThat(message.fields()).singleElement()
				.satisfies(field -> assertThat(field.value()).isEqualTo("IllegalStateException: DB 끊김"));
		});
	}

	@Test
	@DisplayName("소요 시간은 1분 미만이면 초만, 넘으면 분·초로 적는다")
	void format() {
		assertThat(SearchMissTriageReport.format(Duration.ofSeconds(42))).isEqualTo("42초");
		assertThat(SearchMissTriageReport.format(Duration.ofSeconds(252))).isEqualTo("4분 12초");
	}
}
