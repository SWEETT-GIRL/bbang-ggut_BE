package sweetgirl.croissant.searchmiss.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import sweetgirl.croissant.notification.event.OperationalNotificationEvent;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Channel;
import sweetgirl.croissant.notification.event.OperationalNotificationEvent.Field;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

/**
 * 일일 판정 배치의 Slack 리포트.
 *
 * <p>"돌았나"와 "무엇을 어떻게 처리했나"를 한 메시지로 본다. 처리할 행이 없어도 보낸다 — 06 시 리포트가 안 와 있으면 배치가
 * 안 돈 것이다. 목록은 발생 횟수 순으로 앞쪽만 싣고 나머지는 건수로 줄인다(Slack 블록 글자 수 상한).
 */
public final class SearchMissTriageReport {

	static final int SUCCESS_COLOR = 5763719;
	static final int WARNING_COLOR = 16776960;
	static final int FAILURE_COLOR = 15158332;
	static final int LIST_LIMIT = 10;
	static final int SHORT_LIST_LIMIT = 5;
	private static final String TITLE = "[검색어 판정]";

	private SearchMissTriageReport() {
		// 정적 유틸리티
	}

	public static OperationalNotificationEvent completed(SearchMissTriageBatchResult result, boolean autoApply,
			Duration elapsed) {
		String title = result.isStopped()
			? "⚠️ " + TITLE + " 중단: " + result.stoppedReason()
			: "🥯 " + TITLE + " 완료";
		int color = result.isStopped() || result.failed() > 0 ? WARNING_COLOR : SUCCESS_COLOR;
		return new OperationalNotificationEvent(Channel.SEARCH_MISS, title, summary(result, autoApply, elapsed),
			color, fields(result, autoApply));
	}

	public static OperationalNotificationEvent failed(RuntimeException exception, boolean autoApply,
			Duration elapsed) {
		String description = mode(autoApply) + "\n소요 " + format(elapsed) + "\n배치를 시작하지 못했거나 도중에 끊겼습니다. "
			+ "판정이 안 찍힌 행은 다음 날 다시 집힙니다.";
		List<Field> fields = List.of(new Field("오류", exception.getClass().getSimpleName() + ": "
			+ Objects.toString(exception.getMessage(), ""), false));
		return new OperationalNotificationEvent(Channel.SEARCH_MISS, "🚨 " + TITLE + " 실패", description,
			FAILURE_COLOR, fields);
	}

	private static String summary(SearchMissTriageBatchResult result, boolean autoApply, Duration elapsed) {
		StringBuilder summary = new StringBuilder(mode(autoApply)).append('\n');
		if (result.total() == 0) {
			return summary.append("판정할 검색어가 없었습니다 · 소요 ").append(format(elapsed)).toString();
		}
		summary.append("꺼낸 ").append(result.total()).append("건 · 판정 ").append(result.items().size())
			.append("건 · 실패 ").append(result.failed()).append("건");
		if (result.skipped() > 0) {
			summary.append(" · 못 본 ").append(result.skipped()).append("건(내일 다시)");
		}
		return summary.append(" · 소요 ").append(format(elapsed)).toString();
	}

	private static String mode(boolean autoApply) {
		return autoApply
			? "모드: *auto-apply* — 별칭 추가·처리 상태 변경까지 적용"
			: "모드: *shadow* — 판정만 기록, 별칭·처리 상태는 그대로";
	}

	private static List<Field> fields(SearchMissTriageBatchResult result, boolean autoApply) {
		List<Field> fields = new ArrayList<>();
		if (result.total() == 0) {
			return fields;
		}
		Map<SearchMissVerdict, Integer> counts = result.verdicts();
		fields.add(new Field("판정별 건수", verdictCounts(counts, autoApply), false));
		addList(fields, "별칭 추가 (ALIAS_RESOLVED)", itemsOf(result, SearchMissVerdict.ALIAS_RESOLVED), LIST_LIMIT,
			SearchMissTriageReport::aliasLine);
		addList(fields, "👀 신규 가게 후보 (NEW_STORE_CANDIDATE)",
			itemsOf(result, SearchMissVerdict.NEW_STORE_CANDIDATE), LIST_LIMIT, SearchMissTriageReport::newStoreLine);
		addList(fields, "👀 삭제된 가게와 일치 (EXISTS_BUT_DELETED)",
			itemsOf(result, SearchMissVerdict.EXISTS_BUT_DELETED), LIST_LIMIT, SearchMissTriageReport::storeLine);
		addList(fields, "👀 판단 보류 (UNRESOLVED) — 많이 찾힌 순", itemsOf(result, SearchMissVerdict.UNRESOLVED),
			SHORT_LIST_LIMIT, SearchMissTriageReport::reasonLine);
		addList(fields, "가게 아님 (NOT_A_STORE)", itemsOf(result, SearchMissVerdict.NOT_A_STORE), SHORT_LIST_LIMIT,
			SearchMissTriageReport::keywordLine);
		if (!result.failures().isEmpty()) {
			List<String> lines = result.failures().stream()
				.limit(SHORT_LIST_LIMIT)
				.map(failure -> "• " + failure.keyword() + " — " + failure.reason())
				.collect(Collectors.toCollection(ArrayList::new));
			appendRemainder(lines, result.failures().size(), SHORT_LIST_LIMIT);
			fields.add(new Field("❌ 실패 (내일 다시)", String.join("\n", lines), false));
		}
		return fields;
	}

	private static String verdictCounts(Map<SearchMissVerdict, Integer> counts, boolean autoApply) {
		List<String> lines = new ArrayList<>();
		for (SearchMissVerdict verdict : SearchMissVerdict.values()) {
			Integer count = counts.get(verdict);
			if (count == null) {
				continue;
			}
			lines.add("• " + verdict.name() + " " + count + "건" + outcomeLabel(verdict, autoApply));
		}
		return String.join("\n", lines);
	}

	/** 자동으로 닫히는 판정은 어떤 상태로 바뀌었나(shadow 면 바뀔 예정), 사람이 볼 판정은 그렇다고 적는다. */
	private static String outcomeLabel(SearchMissVerdict verdict, boolean autoApply) {
		SearchMissStatus status = switch (verdict) {
			case JUNK -> SearchMissStatus.IGNORED;
			case NOW_HIT, ALIAS_RESOLVED -> SearchMissStatus.ALREADY_EXISTS;
			default -> null;
		};
		if (status == null) {
			return " → 사람이 확인 (PENDING 유지)";
		}
		return autoApply ? " → " + status.name() : " → " + status.name() + " 예정 (shadow, 미적용)";
	}

	private static List<SearchMissTriageItem> itemsOf(SearchMissTriageBatchResult result, SearchMissVerdict verdict) {
		return result.items().stream()
			.filter(item -> item.decision().verdict() == verdict)
			.sorted(Comparator.comparingLong(SearchMissTriageItem::occurrenceCount).reversed())
			.toList();
	}

	private static void addList(List<Field> fields, String name, List<SearchMissTriageItem> items, int limit,
			Function<SearchMissTriageItem, String> line) {
		if (items.isEmpty()) {
			return;
		}
		List<String> lines = items.stream().limit(limit).map(line).collect(Collectors.toCollection(ArrayList::new));
		appendRemainder(lines, items.size(), limit);
		fields.add(new Field(name + " " + items.size() + "건", String.join("\n", lines), false));
	}

	private static void appendRemainder(List<String> lines, int total, int limit) {
		if (total > limit) {
			lines.add("… 외 " + (total - limit) + "건");
		}
	}

	private static String aliasLine(SearchMissTriageItem item) {
		Map<String, Object> evidence = item.decision().evidence();
		Object storeIds = evidence.get("aliasStoreIds");
		int branchCount = storeIds instanceof List<?> ids ? ids.size() : 0;
		return "• " + head(item) + " → " + Objects.toString(evidence.get("matchedStoreName"), "?")
			+ " (지점 " + branchCount + "곳)";
	}

	private static String newStoreLine(SearchMissTriageItem item) {
		Object candidates = item.decision().evidence().get("newStoreCandidates");
		if (candidates instanceof List<?> places && !places.isEmpty() && places.get(0) instanceof Map<?, ?> place) {
			return "• " + head(item) + " — 네이버: " + place.get("name") + " · " + place.get("category") + " · "
				+ place.get("address");
		}
		return "• " + head(item);
	}

	private static String storeLine(SearchMissTriageItem item) {
		return "• " + head(item) + " → " + Objects.toString(item.decision().evidence().get("matchedStoreName"), "?");
	}

	private static String reasonLine(SearchMissTriageItem item) {
		return "• " + head(item) + " — " + Objects.toString(item.decision().evidence().get("reason"), "");
	}

	private static String keywordLine(SearchMissTriageItem item) {
		return "• " + head(item);
	}

	private static String head(SearchMissTriageItem item) {
		return item.keyword() + " (" + item.occurrenceCount() + "회)";
	}

	static String format(Duration elapsed) {
		long seconds = elapsed.toSeconds();
		return seconds < 60 ? seconds + "초" : (seconds / 60) + "분 " + (seconds % 60) + "초";
	}
}
