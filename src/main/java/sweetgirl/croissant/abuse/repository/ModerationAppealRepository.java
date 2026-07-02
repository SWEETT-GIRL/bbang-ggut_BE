package sweetgirl.croissant.abuse.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
