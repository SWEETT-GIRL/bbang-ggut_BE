package sweetgirl.croissant.abuse.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.ModerationAppealStatus;

public interface ModerationAppealRepository extends JpaRepository<ModerationAppeal, Long> {

	boolean existsByModerationActionIdAndMemberId(Long moderationActionId, Long memberId);

	@EntityGraph(attributePaths = {"moderationAction", "moderationAction.member", "member"})
	Optional<ModerationAppeal> findWithDetailsById(Long id);

	@EntityGraph(attributePaths = {"moderationAction", "moderationAction.member", "member"})
	Page<ModerationAppeal> findAllBy(Pageable pageable);

	@EntityGraph(attributePaths = {"moderationAction", "moderationAction.member", "member"})
	Page<ModerationAppeal> findByStatus(ModerationAppealStatus status, Pageable pageable);

	/** 탈퇴한 지 cutoff 를 넘긴 사람의 이의제기(본인 것 · 그 사람 신고에서 비롯된 조치의 것)를 지운다 — 보관 기한 뒤. */
	@Modifying
	@Query("""
			DELETE FROM ModerationAppeal a
			WHERE a.member.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
			""")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
