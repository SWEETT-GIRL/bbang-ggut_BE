package sweetgirl.croissant.searchmiss.event;

import java.time.LocalDateTime;

import sweetgirl.croissant.store.service.search.SearchOutcome;

/**
 * 검색이 사용자가 입력한 말을 돌려주지 못했을 때 발행한다.
 *
 * <p>검색은 사용자 요청 경로라 적재를 동기로 하면 INSERT 지연이 검색 응답에 얹힌다.
 * 이벤트로 넘겨 비동기로 처리한다.
 *
 * @param outcome {@code HIT}은 발행하지 않는다. 성공한 검색은 처리할 것이 없다.
 */
public record SearchMissEvent(
		String keyword,
		SearchOutcome outcome,
		LocalDateTime occurredAt) {
}
