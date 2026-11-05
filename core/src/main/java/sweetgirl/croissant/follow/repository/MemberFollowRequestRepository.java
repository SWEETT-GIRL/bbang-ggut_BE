package sweetgirl.croissant.follow.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.follow.entity.MemberFollowRequest;
import sweetgirl.croissant.follow.entity.MemberFollowRequestId;

public interface MemberFollowRequestRepository extends JpaRepository<MemberFollowRequest, MemberFollowRequestId> {

	boolean existsByIdRequesterIdAndIdTargetId(Long requesterId, Long targetId);

	long countByIdTargetId(Long targetId);

	/** 오늘 몇 명에게 요청했나 — 하루 팔로우 상한에 요청도 세려고(비공개 계정 요청 도배 방지). */
	long countByIdRequesterIdAndCreatedAtGreaterThanEqual(Long requesterId, LocalDateTime since);

	/** 이미 있으면 0. 이중 탭이 겹쳐도 PK 충돌로 죽지 않는다. */
	@Modifying
	@Query(value = """
			INSERT INTO member_follow_request (requester_id, target_id, created_at, updated_at)
			VALUES (:requesterId, :targetId, :now, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("requesterId") Long requesterId,
			@Param("targetId") Long targetId,
			@Param("now") LocalDateTime now);

	@Modifying
	@Query("""
			DELETE FROM MemberFollowRequest r
			WHERE r.id.requesterId = :requesterId AND r.id.targetId = :targetId
			""")
	int deleteByPair(@Param("requesterId") Long requesterId, @Param("targetId") Long targetId);

	/** 두 사람 사이 요청을 양방향으로 지운다 — 차단 때. */
	@Modifying
	@Query("""
			DELETE FROM MemberFollowRequest r
			WHERE (r.id.requesterId = :memberId AND r.id.targetId = :otherId)
				OR (r.id.requesterId = :otherId AND r.id.targetId = :memberId)
			""")
	int deleteBetween(@Param("memberId") Long memberId, @Param("otherId") Long otherId);

	/** 한 사람의 요청을 양방향으로 전부 지운다 — 탈퇴 때. */
	@Modifying
	@Query("DELETE FROM MemberFollowRequest r WHERE r.id.requesterId = :memberId OR r.id.targetId = :memberId")
	int deleteAllOf(@Param("memberId") Long memberId);

	/** 이 사람이 받은 요청을 전부 지운다 — 공개로 전환해 전부 수락한 뒤. */
	@Modifying
	@Query("DELETE FROM MemberFollowRequest r WHERE r.id.targetId = :targetId")
	int deleteAllByTargetId(@Param("targetId") Long targetId);

	/**
	 * {@code targetId} 가 받은 요청. 최신순. 탈퇴한 사람의 요청은 뺀다(차단은 요청 자체를 지우므로 안 거른다).
	 */
	@Query("""
			SELECT r FROM MemberFollowRequest r
			JOIN FETCH r.requester requester
			WHERE r.id.targetId = :targetId AND requester.deletedAt IS NULL
			ORDER BY r.createdAt DESC, r.id.requesterId DESC
			""")
	Slice<MemberFollowRequest> findReceived(@Param("targetId") Long targetId, Pageable pageable);

	/** 공개 전환 때 전부 수락하려고 — 이 사람에게 요청한 사람 전부. */
	@Query("SELECT r.id.requesterId FROM MemberFollowRequest r WHERE r.id.targetId = :targetId")
	List<Long> findRequesterIdsByTargetId(@Param("targetId") Long targetId);

	/** {@code requesterId} 가 이 사람들 중 누구에게 요청해 뒀나 — 목록 줄의 followRequestedByMe 를 한 번에. */
	@Query("""
			SELECT r.id.targetId FROM MemberFollowRequest r
			WHERE r.id.requesterId = :requesterId AND r.id.targetId IN :memberIds
			""")
	List<Long> findTargetIdsIn(@Param("requesterId") Long requesterId,
			@Param("memberIds") Collection<Long> memberIds);
}
