package sweetgirl.dataops.snapshot.service;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import tools.jackson.databind.ObjectMapper;

class RegularHoursTrackerTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final RegularHoursTracker tracker = new RegularHoursTracker(new BusinessHoursParser(), objectMapper);

	private static final LocalDateTime DAY1 = LocalDateTime.of(2026, 9, 26, 13, 15);
	private static final LocalDateTime DAY2 = LocalDateTime.of(2026, 9, 27, 9, 48);

	/** 월~토 08:30~19:00, 일 휴무 — 날짜 없는 정기 일정 */
	private static final String REGULAR_WEEK = "["
		+ "{\"day\":\"월\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"화\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"수\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"목\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"금\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"토\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}},"
		+ "{\"day\":\"일\",\"businessHours\":null,\"description\":\"정기휴무 (매주 일요일)\"}]";

	private String known() {
		return tracker.observe(null, REGULAR_WEEK, DAY1).knownAfter();
	}

	@Test
	void 날짜_붙은_임시_요일은_비교하지_않는다_회전_주간() {
		// 9/26 토요일 추석 휴무로 "토(9/26)" — 그 요일의 정기 값은 이 관측에서 보이지 않는다
		String holidayWeek = REGULAR_WEEK.replace(
			"{\"day\":\"토\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}}",
			"{\"day\":\"토(9/26)\",\"businessHours\":null,\"description\":\"추석 휴무\"}");

		RegularHoursTracker.Observation holiday = tracker.observe(known(), holidayWeek, DAY1);
		RegularHoursTracker.Observation after = tracker.observe(holiday.knownAfter(), REGULAR_WEEK, DAY2);

		assertThat(holiday.changed()).isFalse();
		assertThat(after.changed()).isFalse();
		// 임시 일정 중에도 토요일의 정기 값 기억은 남아 있다
		assertThat(holiday.knownAfter()).contains("\"SATURDAY\":{\"open\":\"08:30\"");
	}

	@Test
	void 공통_요일의_시간이_바뀌면_변경이다() {
		String later = REGULAR_WEEK.replace(
			"{\"day\":\"금\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}}",
			"{\"day\":\"금\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"22:00\"}}");

		RegularHoursTracker.Observation observation = tracker.observe(known(), later, DAY2);

		assertThat(observation.changed()).isTrue();
		assertThat(observation.changedDays()).containsExactly("FRIDAY");
		assertThat(observation.knownAfter()).contains("\"FRIDAY\":{\"open\":\"08:30\",\"close\":\"22:00\"");
	}

	@Test
	void 기억에_없던_요일이_처음_보이면_변경이다() {
		// 백필 때 날짜가 붙어 있던 요일 — 재판정이 그 요일을 운영과 대조하려면 스냅샷이 저장돼야 한다
		String withoutSunday = "[{\"day\":\"월\",\"businessHours\":{\"start\":\"08:30\",\"end\":\"19:00\"}}]";
		String onlyMonday = tracker.observe(null, withoutSunday, DAY1).knownAfter();

		RegularHoursTracker.Observation observation = tracker.observe(onlyMonday, REGULAR_WEEK, DAY2);

		assertThat(observation.changed()).isTrue();
		assertThat(observation.changedDays()).isEmpty();
		assertThat(observation.firstSeenDays()).contains("SUNDAY", "SATURDAY");
	}

	@Test
	void 매일과_요일별_일곱줄은_값이_같으면_변경이_아니다() {
		String everyDay = "[{\"day\":\"매일\",\"businessHours\":{\"start\":\"10:00\",\"end\":\"21:00\"}}]";
		StringBuilder sevenLines = new StringBuilder("[");
		for (String day : new String[] {"월", "화", "수", "목", "금", "토", "일"}) {
			sevenLines.append(sevenLines.length() > 1 ? "," : "")
				.append("{\"day\":\"").append(day).append("\",\"businessHours\":{\"start\":\"10:00\",\"end\":\"21:00\"}}");
		}
		sevenLines.append("]");

		RegularHoursTracker.Observation observation =
			tracker.observe(tracker.observe(null, everyDay, DAY1).knownAfter(), sevenLines.toString(), DAY2);

		assertThat(observation.changed()).isFalse();
	}

	@Test
	void 설명_문구만_바뀌면_변경이_아니다() {
		String reworded = REGULAR_WEEK.replace("정기휴무 (매주 일요일)", "매주 일요일 휴무");

		assertThat(tracker.observe(known(), reworded, DAY2).changed()).isFalse();
	}

	@Test
	void 영업시간이_없는_관측은_아무것도_바꾸지_않는다() {
		RegularHoursTracker.Observation observation = tracker.observe(known(), null, DAY2);

		assertThat(observation.changed()).isFalse();
		assertThat(observation.knownAfter()).isEqualTo(known());
	}

	@Test
	void 무변경_관측도_그_요일의_관측_시각을_앞으로_민다() {
		RegularHoursTracker.Observation observation = tracker.observe(known(), REGULAR_WEEK, DAY2);

		assertThat(observation.changed()).isFalse();
		assertThat(observation.knownAfter()).contains("2026-09-27T09:48").doesNotContain("2026-09-26T13:15");
	}

	@Test
	void 추적_전이면_보인_요일이_모두_처음이다() {
		RegularHoursTracker.Observation observation = tracker.observe(null, REGULAR_WEEK, DAY1);

		assertThat(observation.firstSeenDays()).hasSize(7);
		assertThat(tracker.observe(null, null, DAY1).knownAfter()).isEqualTo("{}");
	}
}
