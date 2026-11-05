package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.BreadRecordLike;
import sweetgirl.croissant.breadlog.entity.BreadRecordLikeId;

public interface BreadRecordLikeRepository extends JpaRepository<BreadRecordLike, BreadRecordLikeId> {

	long countByIdRecordId(Long recordId);

	boolean existsByIdRecordIdAndIdMemberId(Long recordId, Long memberId);

	/** 이미 눌렀으면 0. 이중 탭이 PK 충돌로 죽지 않는다. */
	@Modifying
	@Query(value = """
			INSERT INTO record_like (record_id, member_id, created_at, updated_at)
			VALUES (:recordId, :memberId, :now, :now)
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("recordId") Long recordId, @Param("memberId") Long memberId,
			@Param("now") LocalDateTime now);

	@Modifying
	@Query("DELETE FROM BreadRecordLike l WHERE l.id.recordId = :recordId AND l.id.memberId = :memberId")
	int deleteByPair(@Param("recordId") Long recordId, @Param("memberId") Long memberId);

	/** 한 사람이 누른 좋아요를 전부 지운다 — 탈퇴 때. 받은 좋아요는 기록에 남는다(기록도 안 보일 뿐 남으니). */
	@Modifying
	@Query("DELETE FROM BreadRecordLike l WHERE l.id.memberId = :memberId")
	int deleteAllByMemberId(@Param("memberId") Long memberId);

	/** 목록 줄마다 좋아요 수 — 한 번에. */
	@Query("SELECT l.id.recordId AS recordId, COUNT(l) AS likeCount FROM BreadRecordLike l "
		+ "WHERE l.id.recordId IN :recordIds GROUP BY l.id.recordId")
	List<RecordLikeCountProjection> countByRecordIdIn(@Param("recordIds") Collection<Long> recordIds);

	/** 이 사람이 누른 기록 id — 목록 줄의 likedByMe. */
	@Query("""
			SELECT l.id.recordId FROM BreadRecordLike l
			WHERE l.id.memberId = :memberId AND l.id.recordId IN :recordIds
			""")
	List<Long> findRecordIdsLikedBy(@Param("memberId") Long memberId, @Param("recordIds") Collection<Long> recordIds);

	/** 좋아요 누른 사람 목록 — 최신순, 탈퇴 · 보는 사람과 차단 관계는 뺀다. */
	@Query("""
			SELECT l FROM BreadRecordLike l
			JOIN FETCH l.member member
			WHERE l.id.recordId = :recordId
				AND member.deletedAt IS NULL
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :viewerId AND b.blockedMember.id = member.id)
						OR (b.blocker.id = member.id AND b.blockedMember.id = :viewerId))
			ORDER BY l.createdAt DESC, l.id.memberId DESC
			""")
	Slice<BreadRecordLike> findLikers(@Param("recordId") Long recordId, @Param("viewerId") Long viewerId,
			Pageable pageable);

	interface RecordLikeCountProjection {

		Long getRecordId();

		long getLikeCount();
	}
}
