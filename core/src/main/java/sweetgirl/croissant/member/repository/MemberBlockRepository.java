package sweetgirl.croissant.member.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.member.entity.MemberBlock;

public interface MemberBlockRepository extends JpaRepository<MemberBlock, Long> {

	Optional<MemberBlock> findByBlockerIdAndBlockedMemberId(Long blockerId, Long blockedMemberId);

	void deleteByBlockerIdAndBlockedMemberId(Long blockerId, Long blockedMemberId);

	@EntityGraph(attributePaths = "blockedMember")
	List<MemberBlock> findAllByBlockerIdOrderByCreatedAtDescIdDesc(Long blockerId, Pageable pageable);
}
