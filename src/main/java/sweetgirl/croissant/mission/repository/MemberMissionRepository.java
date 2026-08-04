package sweetgirl.croissant.mission.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.mission.entity.MemberMission;
import sweetgirl.croissant.mission.entity.MemberMissionId;

public interface MemberMissionRepository extends JpaRepository<MemberMission, MemberMissionId> {

	@EntityGraph(attributePaths = "mission")
	List<MemberMission> findAllByMemberId(Long memberId);

	/** 완료는 한 번만 기록한다 — 두 번째 조회가 완료 시각을 지금으로 밀지 않게. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			INSERT INTO member_missions (member_id, mission_id, completed_at)
			VALUES (:memberId, :missionId, CURRENT_TIMESTAMP)
			ON CONFLICT (member_id, mission_id) DO NOTHING
			""", nativeQuery = true)
	int completeIfAbsent(@Param("memberId") Long memberId, @Param("missionId") Long missionId);

	/** 게스트가 회원으로 승격될 때 — 같은 미션이 양쪽에 있으면 먼저 끝낸 시각을 남긴다. */
	@Modifying(clearAutomatically = true)
	@Query(value = """
			UPDATE member_missions target
			SET completed_at = LEAST(target.completed_at, guest.completed_at)
			FROM member_missions guest
			WHERE target.member_id = :targetId
			AND guest.member_id = :guestId
			AND target.mission_id = guest.mission_id
			""", nativeQuery = true)
	int mergeGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = """
			DELETE FROM member_missions guest
			WHERE guest.member_id = :guestId
			AND EXISTS (
				SELECT 1 FROM member_missions target
				WHERE target.member_id = :targetId
				AND target.mission_id = guest.mission_id
			)
			""", nativeQuery = true)
	int deleteGuestDuplicates(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Modifying(clearAutomatically = true)
	@Query(value = "UPDATE member_missions SET member_id = :targetId WHERE member_id = :guestId", nativeQuery = true)
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);
}
