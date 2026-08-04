package sweetgirl.croissant.badge.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.badge.entity.MemberBadge;
import sweetgirl.croissant.badge.entity.MemberBadgeId;

public interface MemberBadgeRepository extends JpaRepository<MemberBadge, MemberBadgeId> {

	@EntityGraph(attributePaths = "badge")
	List<MemberBadge> findAllByMemberId(Long memberId);

	@EntityGraph(attributePaths = "badge")
	Optional<MemberBadge> findByMemberIdAndBadgeCode(Long memberId, String code);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_badges (member_id, badge_id, achieved_at)
			VALUES (:memberId, :badgeId, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id, badge_id) DO NOTHING
			""", nativeQuery = true)
	int awardIfAbsent(@Param("memberId") Long memberId, @Param("badgeId") Long badgeId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_badges target
			SET achieved_at = LEAST(target.achieved_at, guest.achieved_at),
				celebrated_at = CASE
					WHEN target.celebrated_at IS NULL THEN guest.celebrated_at
					WHEN guest.celebrated_at IS NULL THEN target.celebrated_at
					ELSE LEAST(target.celebrated_at, guest.celebrated_at)
				END
			FROM member_badges guest
			WHERE target.member_id = :targetId
			AND guest.member_id = :guestId
			AND target.badge_id = guest.badge_id
			""", nativeQuery = true)
	int mergeGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM member_badges guest
			WHERE guest.member_id = :guestId
			AND EXISTS (
				SELECT 1 FROM member_badges target
				WHERE target.member_id = :targetId
				AND target.badge_id = guest.badge_id
			)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE member_badges SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
