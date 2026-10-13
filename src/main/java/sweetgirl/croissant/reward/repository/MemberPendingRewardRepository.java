package sweetgirl.croissant.reward.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.reward.entity.MemberPendingReward;

public interface MemberPendingRewardRepository extends JpaRepository<MemberPendingReward, Long> {

	List<MemberPendingReward> findByMemberIdOrderByIdAsc(Long memberId);

	boolean existsByMemberIdAndDedupeKey(Long memberId, String dedupeKey);

	/** 연출을 끝낸 것만 지운다. 남의 id 가 섞여 와도 내 것만 지워진다. */
	@Modifying
	@Query("DELETE FROM MemberPendingReward pending WHERE pending.memberId = :memberId AND pending.id IN :ids")
	int deleteByMemberIdAndIdIn(@Param("memberId") Long memberId, @Param("ids") Collection<Long> ids);
}
