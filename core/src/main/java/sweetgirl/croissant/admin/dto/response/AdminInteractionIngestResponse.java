package sweetgirl.croissant.admin.dto.response;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import sweetgirl.croissant.personalization.dto.response.InteractionIngestResult;

/** 신호 적재 수동 실행 결과. */
public record AdminInteractionIngestResponse(LocalDate from, LocalDate to, int inserted, Map<String, Integer> byType) {
	public static AdminInteractionIngestResponse of(InteractionIngestResult result) {
		Map<String, Integer> byType = new LinkedHashMap<>();
		result.byType().forEach((type, count) -> byType.put(type.name(), count));
		return new AdminInteractionIngestResponse(result.from(), result.to(), result.inserted(), byType);
	}
}
