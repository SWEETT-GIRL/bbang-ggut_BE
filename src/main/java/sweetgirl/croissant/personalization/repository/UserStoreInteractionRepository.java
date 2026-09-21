package sweetgirl.croissant.personalization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.personalization.entity.UserStoreInteraction;

public interface UserStoreInteractionRepository extends JpaRepository<UserStoreInteraction, Long> {

	/**
	 * 게스트 → 회원 병합(#663 ④)에서 회원과 겹치는 게스트 신호를 지운다. 유니크(NULLS NOT DISTINCT) 충돌을 피하는 것이 먼저지만,
	 * 의미상 중복도 같이 없앤다 — 안 그러면 한 사람의 한 행동이 두 번 세어진다.
	 *
	 * <ul>
	 * <li>STORE_VIEW: 적재가 회원 · 가게 · 날 단위로 한 행이라 <b>같은 날</b>이면 중복이다. 남겨 두면 계산기의 "두 날 이상 보고
	 * 안 담음"(−0.5)이 하루짜리에 걸린다.</li>
	 * <li>SAVE · PLAN: 가게당 한 번 있는 상태 신호라 시각이 달라도 <b>같은 가게</b>면 중복이다(찜 표 자체도 중복을 지운다).</li>
	 * <li>나머지(담기 · 해제 · 기록 · 추천 반응): 같은 사건이 여러 번 있을 수 있어 키가 정확히 같을 때만 중복이다.</li>
	 * </ul>
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM user_store_interaction g
			WHERE g.member_id = :guestId
				AND EXISTS (SELECT 1 FROM user_store_interaction t
					WHERE t.member_id = :targetId AND t.type = g.type
						AND t.store_id IS NOT DISTINCT FROM g.store_id
						AND t.menu_id IS NOT DISTINCT FROM g.menu_id
						AND CASE g.type
							WHEN 'STORE_VIEW' THEN CAST(t.occurred_at AS date) = CAST(g.occurred_at AS date)
							WHEN 'SAVE' THEN TRUE
							WHEN 'PLAN' THEN TRUE
							ELSE t.occurred_at = g.occurred_at
						END)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE user_store_interaction SET member_id = :targetId WHERE member_id = :guestId
			""", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
