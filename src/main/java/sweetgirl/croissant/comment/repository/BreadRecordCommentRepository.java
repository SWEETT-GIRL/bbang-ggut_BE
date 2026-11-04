package sweetgirl.croissant.comment.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.comment.entity.BreadRecordComment;

public interface BreadRecordCommentRepository extends JpaRepository<BreadRecordComment, Long> {

	/**
	 * 빵로그 댓글 — 오래된 순. 지운 것 · 숨긴 것(본인 것은 {@code viewerId} 가 쓴 거면 보임) · 보는 사람과 차단 관계인 사람 것은 뺀다.
	 * 탈퇴한 사람의 댓글은 남긴다(작성자만 가린다).
	 */
	@Query("""
			SELECT c FROM BreadRecordComment c
			JOIN FETCH c.member member
			WHERE c.record.id = :recordId
				AND c.deletedAt IS NULL
				AND (c.hiddenAt IS NULL OR member.id = :viewerId)
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :viewerId AND b.blockedMember.id = member.id)
						OR (b.blocker.id = member.id AND b.blockedMember.id = :viewerId))
			ORDER BY c.createdAt ASC, c.id ASC
			""")
	Slice<BreadRecordComment> findVisibleByRecordId(@Param("recordId") Long recordId,
			@Param("viewerId") Long viewerId, Pageable pageable);

	/** 상세의 첫 댓글 1개 — 목록과 같은 조건. */
	@Query("""
			SELECT c FROM BreadRecordComment c
			JOIN FETCH c.member member
			WHERE c.record.id = :recordId
				AND c.deletedAt IS NULL AND c.hiddenAt IS NULL
				AND NOT EXISTS (SELECT 1 FROM MemberBlock b
					WHERE (b.blocker.id = :viewerId AND b.blockedMember.id = member.id)
						OR (b.blocker.id = member.id AND b.blockedMember.id = :viewerId))
			ORDER BY c.createdAt ASC, c.id ASC
			LIMIT 1
			""")
	Optional<BreadRecordComment> findFirstVisibleByRecordId(@Param("recordId") Long recordId,
			@Param("viewerId") Long viewerId);

	/** 댓글 수 — 지운 것 · 숨긴 것은 세지 않는다(차단은 세지 않고 그대로 둔다). */
	@Query("""
			SELECT COUNT(c) FROM BreadRecordComment c
			WHERE c.record.id = :recordId AND c.deletedAt IS NULL AND c.hiddenAt IS NULL
			""")
	long countVisibleByRecordId(@Param("recordId") Long recordId);

	@Query("""
			SELECT c.record.id AS recordId, COUNT(c) AS commentCount FROM BreadRecordComment c
			WHERE c.record.id IN :recordIds AND c.deletedAt IS NULL AND c.hiddenAt IS NULL
			GROUP BY c.record.id
			""")
	List<RecordCommentCountProjection> countVisibleByRecordIdIn(@Param("recordIds") Collection<Long> recordIds);

	/** 도배 제한 — 이 사람이 최근에 쓴 댓글 수(지운 것 포함). */
	long countByMemberIdAndCreatedAtAfter(Long memberId, LocalDateTime after);

	/** 멘션 후보 — 이 빵로그에 댓글 단 사람(지운 댓글 제외). */
	@Query("""
			SELECT DISTINCT c.member.id FROM BreadRecordComment c
			WHERE c.record.id = :recordId AND c.deletedAt IS NULL
			""")
	List<Long> findCommenterIds(@Param("recordId") Long recordId);

	@Query("""
			SELECT c FROM BreadRecordComment c
			JOIN FETCH c.record r JOIN FETCH r.member
			WHERE c.id = :commentId AND c.deletedAt IS NULL
			""")
	Optional<BreadRecordComment> findActiveByIdWithRecord(@Param("commentId") Long commentId);

	/** 답글을 달 부모 — 같은 빵로그의, 지우지도 숨기지도 않은 댓글. 부모의 부모까지 같이 읽는다(깊이 1 로 접으려고). */
	@Query("""
			SELECT c FROM BreadRecordComment c
			JOIN FETCH c.member LEFT JOIN FETCH c.parent
			WHERE c.id = :commentId AND c.record.id = :recordId AND c.deletedAt IS NULL AND c.hiddenAt IS NULL
			""")
	Optional<BreadRecordComment> findReplyTarget(@Param("commentId") Long commentId, @Param("recordId") Long recordId);

	/** 부모를 지울 때 같이 지울 답글 — 아직 안 지운 것. */
	@Query("SELECT c FROM BreadRecordComment c WHERE c.parent.id = :parentId AND c.deletedAt IS NULL")
	List<BreadRecordComment> findActiveReplies(@Param("parentId") Long parentId);

	interface RecordCommentCountProjection {

		Long getRecordId();

		long getCommentCount();
	}
}
