package sweetgirl.croissant.searchmiss.service;

/**
 * 배치에서 판정한 행 하나 — 리포트에 "무엇을 어떻게 처리했나"를 보여 주는 단위.
 *
 * @param occurrenceCount 판정 당시 발생 횟수. 리포트에서 많이 찾힌 것부터 보여 준다
 */
public record SearchMissTriageItem(
		String keyword,
		long occurrenceCount,
		TriageDecision decision) {
}
