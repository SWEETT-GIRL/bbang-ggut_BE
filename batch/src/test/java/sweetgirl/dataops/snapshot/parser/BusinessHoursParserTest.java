package sweetgirl.dataops.snapshot.parser;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class BusinessHoursParserTest {

	private final BusinessHoursParser parser = new BusinessHoursParser();

	@Test
	void 실페이로드_형태를_요일_행으로_정규화한다() {
		// 네이버 원문(참조 해소 후) 축약 — 화·수 영업, 월 정기휴무
		String raw = """
			[{"day":"화","businessHours":{"start":"08:30","end":"19:00"},"description":null,
			  "breakHours":[],"lastOrderTimes":[{"time":"16:55","type":"영업시간"}]},
			 {"day":"수","businessHours":{"start":"08:30","end":"19:00"},"description":null},
			 {"day":"월","businessHours":null,"description":"정기휴무 (매주 월요일)"}]
			""";

		List<BusinessHourRow> rows = parser.parse(raw);

		assertThat(rows).containsExactly(
			new BusinessHourRow("MONDAY", null, null, true),
			new BusinessHourRow("TUESDAY", "08:30", "19:00", false),
			new BusinessHourRow("WEDNESDAY", "08:30", "19:00", false));
	}

	@Test
	void 임시휴무_주간의_날짜_병기_요일은_스킵한다() {
		// "토(8/1)" — 임시 상태를 정기 패턴으로 오염시키지 않는다
		String raw = """
			[{"day":"토(8/1)","businessHours":null,"description":"휴무"},
			 {"day":"일","businessHours":{"start":"10:00","end":"20:00"}}]
			""";

		List<BusinessHourRow> rows = parser.parse(raw);

		assertThat(rows).containsExactly(new BusinessHourRow("SUNDAY", "10:00", "20:00", false));
	}

	@Test
	void 매일_한_줄은_7요일로_편다() {
		// 네이버 실원문(스냅샷 104888) — 요일마다 같으면 "매일" 한 줄만 온다. 이 줄을 버리면 한 요일도 읽히지 않는다
		String raw = """
			[{"day":"매일","__typename":"WorkingHoursInfo","breakHours":null,"description":null,
			  "businessHours":{"end":"21:50","start":"11:00","__typename":"StartEndTime"},
			  "lastOrderTimes":null,"showEndsNextDay":false}]
			""";

		List<BusinessHourRow> rows = parser.parse(raw);

		assertThat(rows).hasSize(7);
		assertThat(rows).extracting(BusinessHourRow::dayOfWeek)
			.containsExactly("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
		assertThat(rows).allMatch(row -> "11:00".equals(row.openTime()) && "21:50".equals(row.closeTime()) && !row.closed());
	}

	@Test
	void 개별_요일_줄이_매일보다_우선한다_순서와_무관하게() {
		String dailyFirst = """
			[{"day":"매일","businessHours":{"start":"09:00","end":"18:00"}},
			 {"day":"월","businessHours":null,"description":"정기휴무 (매주 월요일)"}]
			""";
		String dailyLast = """
			[{"day":"월","businessHours":null,"description":"정기휴무 (매주 월요일)"},
			 {"day":"매일","businessHours":{"start":"09:00","end":"18:00"}}]
			""";

		for (String raw : List.of(dailyFirst, dailyLast)) {
			List<BusinessHourRow> rows = parser.parse(raw);
			assertThat(rows).hasSize(7);
			assertThat(rows.get(0)).isEqualTo(new BusinessHourRow("MONDAY", null, null, true));
			assertThat(rows.get(6)).isEqualTo(new BusinessHourRow("SUNDAY", "09:00", "18:00", false));
		}
	}

	@Test
	void 매일과_날짜_병기_요일이_함께면_날짜_요일은_무시하고_매일로_채운다() {
		// 임시 표기는 정기 패턴이 아니다 — 그 요일도 정기 값(매일)을 따른다
		String raw = """
			[{"day":"토(9/26)","businessHours":null,"description":"휴무"},
			 {"day":"매일","businessHours":{"start":"10:00","end":"24:00"}}]
			""";

		List<BusinessHourRow> rows = parser.parse(raw);

		assertThat(rows).hasSize(7);
		assertThat(rows.get(5)).isEqualTo(new BusinessHourRow("SATURDAY", "10:00", "23:59", false));
	}

	@Test
	void 시간도_휴무도_없는_매일은_스킵한다() {
		assertThat(parser.parse("[{\"day\":\"매일\",\"businessHours\":null,\"description\":null}]")).isEmpty();
	}

	@Test
	void 자정_24시는_운영_time_한계로_2359로_보정한다() {
		String raw = "[{\"day\":\"금\",\"businessHours\":{\"start\":\"00:00\",\"end\":\"24:00\"}}]";

		List<BusinessHourRow> rows = parser.parse(raw);

		assertThat(rows).containsExactly(new BusinessHourRow("FRIDAY", "00:00", "23:59", false));
	}

	@Test
	void 시간도_휴무_표기도_없는_요일은_스킵한다() {
		String raw = "[{\"day\":\"목\",\"businessHours\":null,\"description\":null}]";

		assertThat(parser.parse(raw)).isEmpty();
	}

	@Test
	void 원문이_없거나_형식이_다르면_빈_리스트다() {
		assertThat(parser.parse(null)).isEmpty();
		assertThat(parser.parse(" ")).isEmpty();
		assertThat(parser.parse("{\"not\":\"array\"}")).isEmpty();
		assertThat(parser.parse("깨진 JSON")).isEmpty();
	}

	@Test
	void 같은_요일이_중복되면_첫_항목을_채택한다() {
		String raw = """
			[{"day":"월","businessHours":{"start":"09:00","end":"18:00"}},
			 {"day":"월","businessHours":{"start":"10:00","end":"20:00"}}]
			""";

		List<BusinessHourRow> rows = parser.parse(raw);

		assertThat(rows).containsExactly(new BusinessHourRow("MONDAY", "09:00", "18:00", false));
	}
}
