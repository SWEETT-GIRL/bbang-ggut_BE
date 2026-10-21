package sweetgirl.croissant.breadlog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.StorePlan;
import sweetgirl.croissant.breadlog.entity.StorePlanId;

public interface StorePlanRepository extends JpaRepository<StorePlan, StorePlanId> {

	@Query("""
			SELECT plan
			FROM StorePlan plan
			WHERE plan.member.id = :memberId
			ORDER BY plan.updatedAt DESC, plan.createdAt DESC
			""")
	List<StorePlan> findAllByMemberId(@Param("memberId") Long memberId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM store_plan guest
			WHERE guest.member_id = :guestId
			AND EXISTS (
				SELECT 1 FROM store_plan target
				WHERE target.member_id = :targetId
				AND target.store_id = guest.store_id
			)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE store_plan SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
