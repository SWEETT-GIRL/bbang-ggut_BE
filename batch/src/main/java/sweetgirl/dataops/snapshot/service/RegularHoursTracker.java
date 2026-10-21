package sweetgirl.dataops.snapshot.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 정기 영업시간 변경 판정 (v3 P2 · docs/hours-change-detection-design.md 3-3).
 *
 * 네이버는 오늘부터 7일을 보여 주고, 임시 일정이 있는 날은 "토(9/26)"처럼 날짜를 붙여 그날 값을 담는다. 그 요일의
 * 정기 값은 그 관측에서 보이지 않는다. 그래서 해시 하나로는 비교할 수 없고, 요일별로 마지막에 본 정기 값을 기억해
 * <b>이번 관측에서 날짜 없이 보인 요일만</b> 비교한다 — 재판정(ReconcileService.overlayHours)과 같은 부분 관측 규칙이다.
 *
 * 값은 재판정이 소비하는 것({@link BusinessHoursParser}: 시작·종료·휴무)만 본다. 설명 문구·브레이크타임·라스트오더,
 * "매일" ↔ 요일별 7줄 같은 표기 차이는 값이 같으면 변경이 아니다.
 *
 * 변경 = 공통 요일의 값이 다름, 또는 처음 보이는 요일이 있음(재판정이 그 요일을 운영과 대조하려면 스냅샷이 저장돼야
 * 한다 — 가게당 많아야 7번).
 */
@Component
@RequiredArgsConstructor
public class RegularHoursTracker {

	private static final List<String> WEEK = List.of(
		"MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

	private final BusinessHoursParser businessHoursParser;
	private final ObjectMapper objectMapper;

	/**
	 * @param changedDays 기억과 값이 다른 요일
	 * @param firstSeenDays 기억에 없던 요일
	 * @param knownAfter 이 관측을 반영한 기억(JSON) — 받아들인 관측이면 변경 여부와 상관없이 저장한다
	 */
	public record Observation(List<String> changedDays, List<String> firstSeenDays, String knownAfter) {

		public boolean changed() {
			return !changedDays.isEmpty() || !firstSeenDays.isEmpty();
		}
	}

	private record KnownDay(String open, String close, boolean closed, String observedAt) {

		boolean sameValue(KnownDay other) {
			return Objects.equals(open, other.open) && Objects.equals(close, other.close) && closed == other.closed;
		}
	}

	/**
	 * @param knownJson 저장된 기억. null 이면 아직 추적 전 — 이번에 보인 요일은 모두 처음 보이는 요일이다
	 * @param businessHoursRaw 이번 관측의 영업시간 원문(ParsedPlace.businessHoursRaw)
	 */
	public Observation observe(String knownJson, String businessHoursRaw, LocalDateTime observedAt) {
		Map<String, KnownDay> known = read(knownJson);
		List<String> changedDays = new ArrayList<>();
		List<String> firstSeenDays = new ArrayList<>();
		String at = observedAt != null ? observedAt.withNano(0).toString() : null;
		for (BusinessHourRow row : businessHoursParser.parse(businessHoursRaw)) {
			KnownDay now = new KnownDay(row.openTime(), row.closeTime(), row.closed(), at);
			KnownDay before = known.get(row.dayOfWeek());
			if (before == null) {
				firstSeenDays.add(row.dayOfWeek());
			} else if (!before.sameValue(now)) {
				changedDays.add(row.dayOfWeek());
			}
			known.put(row.dayOfWeek(), now);
		}
		return new Observation(changedDays, firstSeenDays, write(known));
	}

	private Map<String, KnownDay> read(String knownJson) {
		Map<String, KnownDay> known = new LinkedHashMap<>();
		if (knownJson == null || knownJson.isBlank()) {
			return known;
		}
		JsonNode root = objectMapper.readTree(knownJson);
		for (String day : WEEK) {
			JsonNode node = root.path(day);
			if (node.isObject()) {
				known.put(day, new KnownDay(textOrNull(node, "open"), textOrNull(node, "close"),
					node.path("closed").asBoolean(false), textOrNull(node, "observedAt")));
			}
		}
		return known;
	}

	/** 요일 순으로 쓴다 — 같은 기억이 같은 문자열이 되게 */
	private String write(Map<String, KnownDay> known) {
		ObjectNode root = objectMapper.createObjectNode();
		for (String day : WEEK) {
			KnownDay value = known.get(day);
			if (value == null) {
				continue;
			}
			ObjectNode node = root.putObject(day);
			node.put("open", value.open());
			node.put("close", value.close());
			node.put("closed", value.closed());
			node.put("observedAt", value.observedAt());
		}
		return objectMapper.writeValueAsString(root);
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asString(null);
	}
}
