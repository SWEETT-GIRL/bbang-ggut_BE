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

import sweetgirl.croissant.follow.entity.MemberFollow;
import sweetgirl.croissant.follow.entity.MemberFollowId;

public interface MemberFollowRepository extends JpaRepository<MemberFollow, MemberFollowId> {

	boolean existsByIdFollowerIdAndIdFolloweeId(Long followerId, Long followeeId);

	long countByIdFolloweeId(Long followeeId);

	long countByIdFollowerId(Long followerId);

	/** 오늘 몇 명을 팔로우했나 — 하루 상한용. 날짜 경계는 서비스가 KST 로 잡아 넘긴다. */
	long countByIdFollowerIdAndCreatedAtGreaterThanEqual(Long followerId, LocalDateTime since);

	/** 이미 있으면 0. 이중 탭 · 자동 팔로우가 겹쳐도 PK 충돌로 죽지 않는다. */
	@Modifying
	@Query(value = """
			INSERT INTO member_follow (follower_id, followee_id, created_at, updated_at)
			VALUES (:followerId, :followeeId, :now, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("followerId") Long followerId,
			@Param("followeeId") Long followeeId,
			@Param("now") LocalDateTime now);

	@Modifying
	@Query("DELETE FROM MemberFollow f WHERE f.id.followerId = :followerId AND f.id.followeeId = :followeeId")
	int deleteByPair(@Param("followerId") Long followerId, @Param("followeeId") Long followeeId);

	/** 두 사람 사이의 팔로우를 양방향으로 지운다 — 차단 · 탈퇴 때. */
	@Modifying
	@Query("""
			DELETE FROM MemberFollow f
			WHERE (f.id.followerId = :memberId AND f.id.followeeId = :otherId)
				OR (f.id.followerId = :otherId AND f.id.followeeId = :memberId)
			""")
	int deleteBetween(@Param("memberId") Long memberId, @Param("otherId") Long otherId);

	/** 한 사람의 팔로우를 양방향으로 전부 지운다 — 탈퇴 때. */
	@Modifying
	@Query("DELETE FROM MemberFollow f WHERE f.id.followerId = :memberId OR f.id.followeeId = :memberId")
	int deleteAllOf(@Param("memberId") Long memberId);

	/**
	 * {@code memberId} 의 팔로워. 최신순. 탈퇴한 사람과 보는 사람({@code viewerId})과 차단 관계인 사람은 뺀다.
	 * 보는 사람이 없으면(비로그인) 차단 조건은 아무도 안 거른다. {@code nicknamePrefix} 는 닉네임 앞글자(대소문자 무시) — 비면("") 전부.
	 * null 을 넘기면 안 된다: Postgres 는 {@code :q IS NULL} 의 파라미터 타입을 못 정해 500 이 난다(10/22 dev).
	 */
	@Query("""
			SELECT f FROM MemberFollow f
			JOIN FETCH f.follower follower
			WHERE f.id.followeeId = :memberId
				AND follower.deletedAt IS NULL
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :viewerId AND b.blockedMember.id = follower.id)
						OR (b.blocker.id = follower.id AND b.blockedMember.id = :viewerId))
				AND LOWER(follower.nickname) LIKE LOWER(CONCAT(:q, '%'))
			ORDER BY f.createdAt DESC, f.id.followerId DESC
			""")
	Slice<MemberFollow> findFollowers(@Param("memberId") Long memberId, @Param("viewerId") Long viewerId,
			@Param("q") String nicknamePrefix, Pageable pageable);

	/** {@code memberId} 가 팔로우하는 사람. 조건은 {@link #findFollowers} 와 같다. */
	@Query("""
			SELECT f FROM MemberFollow f
			JOIN FETCH f.followee followee
			WHERE f.id.followerId = :memberId
				AND followee.deletedAt IS NULL
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :viewerId AND b.blockedMember.id = followee.id)
						OR (b.blocker.id = followee.id AND b.blockedMember.id = :viewerId))
				AND LOWER(followee.nickname) LIKE LOWER(CONCAT(:q, '%'))
			ORDER BY f.createdAt DESC, f.id.followeeId DESC
			""")
	Slice<MemberFollow> findFollowings(@Param("memberId") Long memberId, @Param("viewerId") Long viewerId,
			@Param("q") String nicknamePrefix, Pageable pageable);

	/** 팔로잉 소식 팬아웃용 — 이 사람을 팔로우하는 사람 전부. */
	@Query("SELECT f.id.followerId FROM MemberFollow f WHERE f.id.followeeId = :memberId")
	List<Long> findFollowerIds(@Param("memberId") Long memberId);

	/** {@code followerId} 가 이 사람들 중 누구를 팔로우하나 — 목록 줄의 followedByMe 를 한 번에 채운다. */
	@Query("""
			SELECT f.id.followeeId FROM MemberFollow f
			WHERE f.id.followerId = :followerId AND f.id.followeeId IN :memberIds
			""")
	List<Long> findFolloweeIdsIn(@Param("followerId") Long followerId, @Param("memberIds") Collection<Long> memberIds);

	/** 이 사람들 중 누가 {@code followeeId} 를 팔로우하나 — 목록 줄의 followsMe. */
	@Query("""
			SELECT f.id.followerId FROM MemberFollow f
			WHERE f.id.followeeId = :followeeId AND f.id.followerId IN :memberIds
			""")
	List<Long> findFollowerIdsIn(@Param("followeeId") Long followeeId, @Param("memberIds") Collection<Long> memberIds);
}
