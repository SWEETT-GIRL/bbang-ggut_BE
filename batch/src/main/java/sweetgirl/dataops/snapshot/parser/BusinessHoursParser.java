package sweetgirl.dataops.snapshot.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * businessHoursRaw(네이버 요일별 원문 JSON) → 운영 store_business_hour 행 구조 정규화 (field-tracks-plan ①).
 *
 * 손실 매핑 규칙 (운영 스키마가 요일+open/close+휴무만 담는다):
 *  - day "월".."일" → MONDAY..SUNDAY. "토(8/1)" 처럼 날짜가 병기된 임시휴무 주간 표기는 그 요일을 스킵
 *    — 임시 상태를 정기 패턴으로 오염시키지 않는다
 *  - day "매일" → 7요일 모두 같은 값. 같은 원문에 개별 요일 줄이 있으면 그 줄이 우선한다(순서 무관).
 *    네이버는 요일마다 시간이 같으면 "매일" 한 줄만 보낸다 — 이 줄을 버리던 동안 최신 스냅샷 50,747곳 중
 *    8,421곳(16.6%)의 영업시간이 한 요일도 읽히지 않았고, 그중 8,127곳은 운영 영업시간이 비어 있었다(2026-09-27)
 *  - businessHours{start,end} 가 있으면 영업일. end "24:00" 은 운영 time 타입 한계로 "23:59" 보정
 *  - businessHours 가 null 이고 description 에 '휴무'가 있으면 정기휴무(is_closed=true)
 *  - 시간도 휴무 표기도 없으면 스킵(불확실), breakHours·lastOrderTimes 는 버린다 (스키마에 자리 없음)
 *
 * 반환: 요일 순 정렬, 같은 요일 중복은 첫 항목 채택. 원문이 없거나 파싱 불가면 빈 리스트.
 */
@Component
public class BusinessHoursParser {

	private static final Map<String, String> DAY_TO_ENUM = Map.of(
		"월", "MONDAY", "화", "TUESDAY", "수", "WEDNESDAY", "목", "THURSDAY",
		"금", "FRIDAY", "토", "SATURDAY", "일", "SUNDAY");
	private static final List<String> DAY_ORDER = List.of(
		"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
	private static final String EVERY_DAY = "매일";

	private final ObjectMapper objectMapper = new ObjectMapper();

	public List<BusinessHourRow> parse(String businessHoursRaw) {
		if (businessHoursRaw == null || businessHoursRaw.isBlank()) {
			return List.of();
		}
		JsonNode root;
		try {
			root = objectMapper.readTree(businessHoursRaw);
		} catch (RuntimeException e) {
			return List.of();
		}
		if (!root.isArray()) {
			return List.of();
		}

		Map<String, BusinessHourRow> byDay = new LinkedHashMap<>();
		BusinessHourRow everyDay = null;   // "매일" — 개별 요일 줄이 없는 요일에만 채운다
		for (JsonNode entry : root) {
			String label = entry.path("day").asString("");
			boolean isEveryDay = EVERY_DAY.equals(label);
			String day = isEveryDay ? DAY_ORDER.get(0) : DAY_TO_ENUM.get(label);
			if (day == null) {
				continue; // "토(8/1)" 임시 표기 등 — 스킵
			}
			BusinessHourRow row = toRow(day, entry);
			if (row == null) {
				continue;
			}
			if (isEveryDay) {
				everyDay = everyDay != null ? everyDay : row;
			} else {
				byDay.putIfAbsent(day, row);
			}
		}

		List<BusinessHourRow> rows = new ArrayList<>();
		for (String day : DAY_ORDER) {
			BusinessHourRow row = byDay.get(day);
			if (row == null && everyDay != null) {
				row = new BusinessHourRow(day, everyDay.openTime(), everyDay.closeTime(), everyDay.closed());
			}
			if (row != null) {
				rows.add(row);
			}
		}
		return rows;
	}

	/** 한 줄 → 행. 시간이 있으면 영업일, 없고 설명에 '휴무'가 있으면 정기휴무, 둘 다 없으면 null(불확실) */
	private BusinessHourRow toRow(String day, JsonNode entry) {
		JsonNode hours = entry.path("businessHours");
		if (hours.isObject() && hours.path("start").isString() && hours.path("end").isString()) {
			return new BusinessHourRow(day,
				normalizeTime(hours.path("start").asString()),
				normalizeTime(hours.path("end").asString()),
				false);
		}
		if (entry.path("description").asString("").contains("휴무")) {
			return new BusinessHourRow(day, null, null, true);
		}
		return null;
	}

	/** 운영 time(0) 은 24:00 을 못 담는다 → 23:59 보정 */
	private String normalizeTime(String time) {
		return "24:00".equals(time) ? "23:59" : time;
	}
}
