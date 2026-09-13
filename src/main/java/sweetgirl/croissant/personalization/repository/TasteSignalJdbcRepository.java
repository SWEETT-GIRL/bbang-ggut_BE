package sweetgirl.croissant.personalization.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 추천 카드 상태 판정의 재료 — "강한 신호"(저장 · 담기 · 기록) 가게 수를 요청 때 센다 (설계 §1).
 * #663 배치가 user_taste_profile.strong_store_count 를 채우면 그 컬럼으로 바꾸고 이 조회는 뺀다.
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
