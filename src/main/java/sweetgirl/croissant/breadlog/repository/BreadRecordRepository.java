package sweetgirl.croissant.breadlog.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.breadlog.dto.projection.BreadRecordRegionProjection;
import sweetgirl.croissant.breadlog.dto.projection.BreadRecordStoreVisitProjection;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.store.entity.Store;

public interface BreadRecordRepository extends JpaRepository<BreadRecord, Long> {

	Optional<BreadRecord> findByIdAndDeletedAtIsNull(Long id);

	// 코스에서 "다녀왔어요"로 고를 후보 — 그 가게의 내 기록 전부, 최신순.
	List<BreadRecord> findAllByMemberIdAndStoreIdAndDeletedAtIsNullOrderByEatenAtDesc(
			Long memberId, Long storeId);

	long countByMemberIdAndDeletedAtIsNull(Long memberId);

	/** 먹어 본 가게인가 — 그 가게에 안 지운 빵로그가 하나라도 있으면. 내 빵 순위에 담을 수 있는 기준. */
	boolean existsByMemberIdAndStoreIdAndDeletedAtIsNull(Long memberId, Long storeId);

	/**
	 * 내 빵 순위에 담을 후보 — 빵로그를 남긴(안 지운) 가게. 가게 없는 기록·삭제된 가게는 뺀다.
	 * query 는 가게 이름 부분 일치 — 빈 문자열이면 전부.
	 */
	@Query("""
			SELECT DISTINCT record.store
			FROM BreadRecord record
			WHERE record.member.id = :memberId AND record.deletedAt IS NULL AND record.store IS NOT NULL
				AND record.store.status = sweetgirl.croissant.store.enums.StoreStatus.ACTIVE
				AND LOWER(record.store.name) LIKE LOWER(CONCAT('%', :query, '%'))
			ORDER BY record.store.name ASC
			""")
	List<Store> findEatenStores(@Param("memberId") Long memberId, @Param("query") String query);

	@Query(value = """
			SELECT COALESCE(MAX(visits), 0)
			FROM (
				SELECT COUNT(*) AS visits
				FROM bread_record
				WHERE member_id = :memberId
				AND deleted_at IS NULL
				GROUP BY CASE
					WHEN store_id IS NOT NULL THEN 'id:' || CAST(store_id AS VARCHAR)
					ELSE 'name:' || store_name
				END
			) store_visits
			""", nativeQuery = true)
	long findMaxStoreVisitCount(@Param("memberId") Long memberId);

	@Query("""
			SELECT record
			FROM BreadRecord record
			WHERE record.member.id = :memberId
			AND record.deletedAt IS NULL
			AND (CAST(:from AS LocalDateTime) IS NULL OR record.eatenAt >= :from)
			AND (CAST(:to AS LocalDateTime) IS NULL OR record.eatenAt < :to)
			AND (CAST(:storeId AS Long) IS NULL OR record.store.id = :storeId)
			""")
	Slice<BreadRecord> findRecords(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("storeId") Long storeId,
			Pageable pageable);

	// 가게 상세에 쌓이는 공개 기록 — 탈퇴한 작성자와 보는 사람이 차단한 작성자는 뺀다.
	@Query("""
			SELECT record
			FROM BreadRecord record
			JOIN FETCH record.member member
			WHERE record.store.id = :storeId
			AND record.isPublic = true
			AND record.deletedAt IS NULL
			AND member.deletedAt IS NULL
			AND (:viewerMemberId IS NULL OR NOT EXISTS (
				SELECT 1 FROM MemberBlock mb
				WHERE mb.blocker.id = :viewerMemberId
				AND mb.blockedMember.id = member.id
			))
			ORDER BY record.eatenAt DESC, record.id DESC
			""")
	List<BreadRecord> findPublicByStoreId(
			@Param("storeId") Long storeId,
			@Param("viewerMemberId") Long viewerMemberId,
			Pageable pageable);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE BreadRecord record SET record.member.id = :targetId WHERE record.member.id = :guestId")
	int moveOwner(@Param("guestId") Long guestId, @Param("targetId") Long targetId);

	@Query(value = """
			SELECT
				record.store_id AS storeId,
				MIN(record.store_name) AS name,
				COUNT(*) AS visits
			FROM bread_record record
			WHERE record.member_id = :memberId
			AND record.deleted_at IS NULL
			AND record.eaten_at >= :from
			AND record.eaten_at < :to
			GROUP BY
				record.store_id,
				CASE WHEN record.store_id IS NULL
					THEN LOWER(REGEXP_REPLACE(record.store_name, '[[:space:]]+', '', 'g'))
					ELSE NULL
				END
			ORDER BY COUNT(*) DESC, MIN(record.store_name)
			""", nativeQuery = true)
	List<BreadRecordStoreVisitProjection> findStoreVisits(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);

	@Query(value = """
			SELECT DISTINCT
				store.region_1st AS region1st,
				record.region_key AS regionKey
			FROM bread_record record
			LEFT JOIN store ON store.id = record.store_id
			WHERE record.member_id = :memberId
			AND record.deleted_at IS NULL
			AND record.eaten_at >= :from
			AND record.eaten_at < :to
			""", nativeQuery = true)
	List<BreadRecordRegionProjection> findVisitedRegions(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to);
}
