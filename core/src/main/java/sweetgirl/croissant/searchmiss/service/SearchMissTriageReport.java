package sweetgirl.croissant.searchmiss.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import sweetgirl.croissant.external.slack.dto.SlackMessage;
import sweetgirl.croissant.external.slack.dto.SlackMessage.Field;
import sweetgirl.croissant.searchmiss.enums.SearchMissStatus;
import sweetgirl.croissant.searchmiss.enums.SearchMissVerdict;

/**
 * 일일 판정 배치의 Slack 리포트.
 *
 * <p>"돌았나"와 "무엇을 어떻게 처리했나"를 본다. 처리할 행이 없어도 보낸다 — 06 시 리포트가 안 와 있으면 배치가 안 돈 것이다.
 *
 * <p><b>목록을 줄이지 않는다.</b> 판정을 사람이 검토해 자동 적용 범위를 정하므로 "… 외 N건"으로 가리면 판단할 수 없다(운영 1회차 10/17).
 * Slack 은 칸 하나 2,000자·메시지 하나 칸 25개가 한계라, 칸을 나누고 넘치면 메시지를 여러 개(n/N)로 만든다.
 */
public final class SearchMissTriageReport {

	static final int SUCCESS_COLOR = 5763719;
	static final int WARNING_COLOR = 16776960;
	static final int FAILURE_COLOR = 15158332;
	/** 칸 이름·줄바꿈 몫을 남기고 Slack 칸 한도(2,000자) 안쪽 */
	static final int FIELD_VALUE_MAX = 1800;
	/** Slack 메시지 하나의 칸 한도(25)와 머리·요약 블록 몫을 남긴다 */
	static final int FIELDS_PER_MESSAGE = 20;
	private static final String TITLE = "[검색어 판정]";

	private SearchMissTriageReport() {
		// 정적 유틸리티
	}

	public static List<SlackMessage> completed(SearchMissTriageBatchResult result,
			Set<SearchMissVerdict> autoApplyVerdicts, Duration elapsed, Instant reportedAt) {
		String title = result.isStopped()
			? "⚠️ " + TITLE + " 중단: " + result.stoppedReason()
			: "🥯 " + TITLE + " 완료";
		int color = result.isStopped() || result.failed() > 0 ? WARNING_COLOR : SUCCESS_COLOR;
		return paginate(title, summary(result, autoApplyVerdicts, elapsed), color, reportedAt,
			fields(result, autoApplyVerdicts));
	}

	public static List<SlackMessage> failed(RuntimeException exception, Set<SearchMissVerdict> autoApplyVerdicts,
			Duration elapsed, Instant reportedAt) {
		String description = mode(autoApplyVerdicts) + "\n소요 " + format(elapsed)
			+ "\n배치를 시작하지 못했거나 도중에 끊겼습니다. 판정이 안 찍힌 행은 다음 날 다시 집힙니다.";
		List<Field> fields = List.of(new Field("오류", exception.getClass().getSimpleName() + ": "
			+ Objects.toString(exception.getMessage(), ""), false));
		return List.of(new SlackMessage(FAILURE_COLOR, "🚨 " + TITLE + " 실패", description, reportedAt, fields));
	}

	private static String summary(SearchMissTriageBatchResult result, Set<SearchMissVerdict> autoApplyVerdicts,
			Duration elapsed) {
		StringBuilder summary = new StringBuilder(mode(autoApplyVerdicts)).append('\n');
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

	private static String mode(Set<SearchMissVerdict> autoApplyVerdicts) {
		if (autoApplyVerdicts.isEmpty()) {
			return "모드: *shadow* — 판정만 기록, 교정·처리 상태는 그대로";
		}
		return "모드: 자동 적용 *" + autoApplyVerdicts + "* — 나머지 판정은 기록만";
	}

	private static List<Field> fields(SearchMissTriageBatchResult result, Set<SearchMissVerdict> autoApplyVerdicts) {
		List<Field> fields = new ArrayList<>();
		if (result.total() == 0) {
			return fields;
		}
		fields.add(new Field("판정별 건수", verdictCounts(result.verdicts(), autoApplyVerdicts), false));
		String correctedLabel = autoApplyVerdicts.contains(SearchMissVerdict.CORRECTED)
			? "검색어 교정 (CORRECTED)"
			: "검색어 교정 예정 — 미적용 (CORRECTED)";
		addSection(fields, correctedLabel, itemsOf(result, SearchMissVerdict.CORRECTED),
			SearchMissTriageReport::correctionLine);
		addSection(fields, "👀 신규 가게 후보 (NEW_STORE_CANDIDATE)",
			itemsOf(result, SearchMissVerdict.NEW_STORE_CANDIDATE), SearchMissTriageReport::newStoreLine);
		addSection(fields, "👀 삭제된 가게와 일치 (EXISTS_BUT_DELETED)",
			itemsOf(result, SearchMissVerdict.EXISTS_BUT_DELETED), SearchMissTriageReport::storeLine);
		addSection(fields, "👀 판단 보류 (UNRESOLVED) — 많이 찾힌 순", itemsOf(result, SearchMissVerdict.UNRESOLVED),
			SearchMissTriageReport::reasonLine);
		addSection(fields, "가게 아님 (NOT_A_STORE)", itemsOf(result, SearchMissVerdict.NOT_A_STORE),
			SearchMissTriageReport::reasonLine);
		addSection(fields, "지금은 검색됨 (NOW_HIT)", itemsOf(result, SearchMissVerdict.NOW_HIT),
			SearchMissTriageReport::keywordLine);
		addSection(fields, "쓰레기 입력 (JUNK)", itemsOf(result, SearchMissVerdict.JUNK),
			SearchMissTriageReport::reasonLine);
		List<String> failureLines = result.failures().stream()
			.map(failure -> "• " + SearchMissTextMatcher.maskPersonalInfo(failure.keyword()) + " — " + failure.reason())
			.toList();
		addLines(fields, "❌ 실패 (내일 다시) " + failureLines.size() + "건", failureLines);
		return fields;
	}

	private static String verdictCounts(Map<SearchMissVerdict, Integer> counts,
			Set<SearchMissVerdict> autoApplyVerdicts) {
		List<String> lines = new ArrayList<>();
		for (SearchMissVerdict verdict : SearchMissVerdict.values()) {
			Integer count = counts.get(verdict);
			if (count != null) {
				lines.add("• " + verdict.name() + " " + count + "건" + outcomeLabel(verdict, autoApplyVerdicts));
			}
		}
		return String.join("\n", lines);
	}

	/** 자동으로 닫히는 판정은 어떤 상태로 바뀌었나(켜지 않았으면 바뀔 예정), 사람이 볼 판정은 그렇다고 적는다. */
	private static String outcomeLabel(SearchMissVerdict verdict, Set<SearchMissVerdict> autoApplyVerdicts) {
		SearchMissStatus status = switch (verdict) {
			case JUNK -> SearchMissStatus.IGNORED;
			case NOW_HIT, CORRECTED -> SearchMissStatus.ALREADY_EXISTS;
			default -> null;
		};
		if (status == null) {
			return " → 사람이 확인 (PENDING 유지)";
		}
		return autoApplyVerdicts.contains(verdict)
			? " → " + status.name()
			: " → " + status.name() + " 예정 (미적용)";
	}

	private static List<SearchMissTriageItem> itemsOf(SearchMissTriageBatchResult result, SearchMissVerdict verdict) {
		return result.items().stream()
			.filter(item -> item.decision().verdict() == verdict)
			.sorted(Comparator.comparingLong(SearchMissTriageItem::occurrenceCount).reversed())
			.toList();
	}

	private static void addSection(List<Field> fields, String name, List<SearchMissTriageItem> items,
			Function<SearchMissTriageItem, String> line) {
		addLines(fields, name + " " + items.size() + "건", items.stream().map(line).toList());
	}

	/** 줄을 칸 한도 안에서 나눠 담는다. 이어지는 칸은 "(이어서)"를 붙인다. 줄이 없으면 칸도 만들지 않는다. */
	static void addLines(List<Field> fields, String name, List<String> lines) {
		if (lines.isEmpty()) {
			return;
		}
		StringBuilder chunk = new StringBuilder();
		String chunkName = name;
		for (String line : lines) {
			String bounded = line.length() > FIELD_VALUE_MAX ? line.substring(0, FIELD_VALUE_MAX) : line;
			if (chunk.length() > 0 && chunk.length() + 1 + bounded.length() > FIELD_VALUE_MAX) {
				fields.add(new Field(chunkName, chunk.toString(), false));
				chunk.setLength(0);
				chunkName = name + " (이어서)";
			}
			if (chunk.length() > 0) {
				chunk.append('\n');
			}
			chunk.append(bounded);
		}
		fields.add(new Field(chunkName, chunk.toString(), false));
	}

	/** 칸을 메시지마다 {@link #FIELDS_PER_MESSAGE}개씩 나눈다. 여러 개면 제목에 (n/N)을 붙인다. */
	static List<SlackMessage> paginate(String title, String description, int color, Instant reportedAt,
			List<Field> fields) {
		List<List<Field>> pages = new ArrayList<>();
		for (int start = 0; start < fields.size(); start += FIELDS_PER_MESSAGE) {
			pages.add(fields.subList(start, Math.min(start + FIELDS_PER_MESSAGE, fields.size())));
		}
		if (pages.isEmpty()) {
			return List.of(new SlackMessage(color, title, description, reportedAt, List.of()));
		}
		int total = pages.size();
		List<SlackMessage> messages = new ArrayList<>();
		for (int index = 0; index < total; index++) {
			String pageTitle = total == 1 ? title : title + " (" + (index + 1) + "/" + total + ")";
			String pageDescription = index == 0 ? description : "이어서 — " + (index + 1) + "/" + total;
			messages.add(new SlackMessage(color, pageTitle, pageDescription, reportedAt, pages.get(index)));
		}
		return messages;
	}

	private static String correctionLine(SearchMissTriageItem item) {
		Map<String, Object> evidence = item.decision().evidence();
		Object storeIds = evidence.get("matchedStoreIds");
		int storeCount = storeIds instanceof List<?> ids ? ids.size() : 0;
		return "• " + head(item) + " → " + Objects.toString(evidence.get("correctionTarget"), "?")
			+ " (가게 " + storeCount + "곳)";
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
		return SearchMissTextMatcher.maskPersonalInfo(item.keyword()) + " (" + item.occurrenceCount() + "회)";
	}

	static String format(Duration elapsed) {
		long seconds = elapsed.toSeconds();
		return seconds < 60 ? seconds + "초" : (seconds / 60) + "분 " + (seconds % 60) + "초";
	}
}
