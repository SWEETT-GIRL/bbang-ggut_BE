package sweetgirl.croissant.personalization.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 추천 카드 상태 판정의 재료 — "강한 신호"(저장 · 담기 · 기록) 가게 수를 요청 때 센다 (설계 §1).
 * #663 ④ 결정: user_taste_profile.strong_store_count 로 바꾸지 않는다 — 그 컬럼은 하루 한 번(04:00) 창 안 양의 신호를 세고, 이 조회는
 * "지금 저장·담기·기록한 가게"를 세어 방금 저장한 회원도 바로 칩 상태가 된다(하루 지연 없음). 세 표 PK 조회라 싸다.
 */
@Repository
@RequiredArgsConstructor
public class TasteSignalJdbcRepository {

	private static final String STRONG_SIGNAL_STORES = """
			SELECT COUNT(DISTINCT store_id) FROM (
				SELECT ss.store_id FROM saved_stores ss WHERE ss.member_id = :memberId
				UNION
				SELECT cs.store_id FROM course_stores cs
					JOIN courses c ON c.id = cs.course_id
					WHERE c.member_id = :memberId AND c.deleted_at IS NULL AND cs.deleted_at IS NULL
				UNION
				SELECT br.store_id FROM bread_record br
					WHERE br.member_id = :memberId AND br.deleted_at IS NULL AND br.store_id IS NOT NULL
			) signals
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public int countStrongSignalStores(Long memberId) {
		Integer count = jdbcTemplate.queryForObject(STRONG_SIGNAL_STORES,
			new MapSqlParameterSource("memberId", memberId), Integer.class);
		return count != null ? count : 0;
	}
}
