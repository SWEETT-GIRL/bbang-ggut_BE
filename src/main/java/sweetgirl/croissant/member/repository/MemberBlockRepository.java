package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.member.entity.MemberBlock;

public interface MemberBlockRepository extends JpaRepository<MemberBlock, Long> {

	Optional<MemberBlock> findByBlockerIdAndBlockedMemberId(Long blockerId, Long blockedMemberId);

	void deleteByBlockerIdAndBlockedMemberId(Long blockerId, Long blockedMemberId);

	@EntityGraph(attributePaths = "blockedMember")
	List<MemberBlock> findAllByBlockerIdOrderByCreatedAtDescIdDesc(Long blockerId, Pageable pageable);

	/** 어느 쪽이든 차단했는가. 팔로우 · 좋아요 · 댓글 · 톡은 차단한 쪽도 당한 쪽도 못 한다. */
	@Query("""
			SELECT COUNT(b) > 0 FROM MemberBlock b
			WHERE (b.blocker.id = :memberId AND b.blockedMember.id = :otherId)
				OR (b.blocker.id = :otherId AND b.blockedMember.id = :memberId)
			""")
	boolean existsBetween(@Param("memberId") Long memberId, @Param("otherId") Long otherId);
}
