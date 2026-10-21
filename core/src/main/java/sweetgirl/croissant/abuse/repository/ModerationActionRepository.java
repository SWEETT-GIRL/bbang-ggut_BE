package sweetgirl.croissant.abuse.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.abuse.entity.ModerationAction;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, Long> {

	@Query("""
			select a from ModerationAction a
			left join fetch a.member
			left join fetch a.report
			where a.id = :actionId
			""")
	Optional<ModerationAction> findByIdWithMemberAndReport(@Param("actionId") Long actionId);
}
