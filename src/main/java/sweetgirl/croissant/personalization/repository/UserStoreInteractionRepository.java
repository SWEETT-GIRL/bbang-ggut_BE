package sweetgirl.croissant.personalization.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.personalization.entity.UserStoreInteraction;

public interface UserStoreInteractionRepository extends JpaRepository<UserStoreInteraction, Long> {

	/**
	 * 게스트 → 회원 병합(#663 ④): 회원이 이미 같은 사건(type · store · menu · occurred_at, NULL 도 같은 값)을 갖고 있으면
	 * 게스트 행을 먼저 지운다 — uq_user_store_interaction 이 NULLS NOT DISTINCT 라 옮기면 충돌한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM user_store_interaction g
			WHERE g.member_id = :guestId
				AND EXISTS (SELECT 1 FROM user_store_interaction t
					WHERE t.member_id = :targetId AND t.type = g.type
						AND t.store_id IS NOT DISTINCT FROM g.store_id
						AND t.menu_id IS NOT DISTINCT FROM g.menu_id
						AND t.occurred_at = g.occurred_at)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE user_store_interaction SET member_id = :targetId WHERE member_id = :guestId
			""", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
