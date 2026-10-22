package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.entity.BreadRecordCompanion;
import sweetgirl.croissant.breadlog.entity.BreadRecordCompanionId;

public interface BreadRecordCompanionRepository extends JpaRepository<BreadRecordCompanion, BreadRecordCompanionId> {

	/** 뺀 사람까지 전부 — 작성자가 고칠 때 "다시 넣을 수 없는 사람"을 알아야 한다. */
	List<BreadRecordCompanion> findAllByIdRecordId(Long recordId);

	Optional<BreadRecordCompanion> findByIdRecordIdAndIdMemberId(Long recordId, Long memberId);

	/** 상세에 보이는 같이 간 사람 — 스스로 뺀 사람과 탈퇴한 사람은 뺀다. 태그한 순서. */
	@Query("""
			SELECT c FROM BreadRecordCompanion c
			JOIN FETCH c.member member
			WHERE c.id.recordId = :recordId AND c.removedAt IS NULL AND member.deletedAt IS NULL
			ORDER BY c.createdAt ASC, c.id.memberId ASC
			""")
	List<BreadRecordCompanion> findActiveByRecordId(@Param("recordId") Long recordId);

	/** 작성자가 {@code since} 부터 태그한 사람(중복 없이) — 하루 태그 인원 상한용. */
	@Query("""
			SELECT DISTINCT c.id.memberId FROM BreadRecordCompanion c
			WHERE c.record.member.id = :ownerId AND c.createdAt >= :since
			""")
	List<Long> findMemberIdsTaggedSince(@Param("ownerId") Long ownerId, @Param("since") LocalDateTime since);

	/** 두 사람 사이의 태그를 양방향으로 지운다 — 차단 · 탈퇴 때(한쪽이 쓴 기록에 다른 쪽이 태그된 행). */
	@Modifying
	@Query("""
			DELETE FROM BreadRecordCompanion c
			WHERE (c.id.memberId = :memberId AND c.record.member.id = :otherId)
				OR (c.id.memberId = :otherId AND c.record.member.id = :memberId)
			""")
	int deleteBetween(@Param("memberId") Long memberId, @Param("otherId") Long otherId);
}
