package sweetgirl.croissant.abuse.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

	/** 탈퇴한 지 cutoff 를 넘긴 사람의 조치(본인 대상 · 그 사람 신고에서 비롯된 것)를 지운다 — 보관 기한 뒤. 이의제기를 먼저 지운다. */
	@Modifying
	@Query("""
			DELETE FROM ModerationAction ma
			WHERE ma.member.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
				OR ma.report.id IN (SELECT r.id FROM AbuseReport r
					WHERE r.reporter.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff)
						OR r.reportedMember.id IN (SELECT m.id FROM Member m WHERE m.deletedAt < :cutoff))
			""")
	int deleteAllOfMembersDeletedBefore(@Param("cutoff") LocalDateTime cutoff);
}
