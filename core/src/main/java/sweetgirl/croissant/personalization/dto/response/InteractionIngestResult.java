package sweetgirl.croissant.personalization.dto.response;

import java.time.LocalDate;
import java.util.Map;

import sweetgirl.croissant.personalization.enums.InteractionType;

/** 신호 적재 한 번의 결과 — 범위 [from, to) 와 종류별 새로 들어간 행 수(이미 있던 행은 세지 않는다). */
public record InteractionIngestResult(LocalDate from, LocalDate to, int inserted,
		Map<InteractionType, Integer> byType) {
}
