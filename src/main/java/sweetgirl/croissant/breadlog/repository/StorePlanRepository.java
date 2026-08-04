package sweetgirl.croissant.breadlog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.StorePlan;
import sweetgirl.croissant.breadlog.entity.StorePlanId;

public interface StorePlanRepository extends JpaRepository<StorePlan, StorePlanId> {

	@Query("""
			SELECT plan
			FROM StorePlan plan
			WHERE plan.member.id = :memberId
			ORDER BY plan.updatedAt DESC, plan.createdAt DESC
			""")
	List<StorePlan> findAllByMemberId(@Param("memberId") Long memberId);
}
