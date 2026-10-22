package sweetgirl.croissant.member.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/** 탈퇴한 지 cutoff 를 넘긴 사람이 한 차단 · 받은 차단을 지운다 — 보관 기한 뒤. */
	@Modifying
	@Query("""
			DELETE FROM MemberBlock b
			WHERE b.blocker.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
				OR b.blockedMember.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
			""")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
