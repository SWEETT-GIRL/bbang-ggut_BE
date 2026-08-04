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

public interface BreadRecordRepository extends JpaRepository<BreadRecord, Long> {

	Optional<BreadRecord> findByIdAndDeletedAtIsNull(Long id);

	@Query("""
			SELECT record
			FROM BreadRecord record
			WHERE record.member.id = :memberId
			AND record.deletedAt IS NULL
			AND (:from IS NULL OR record.eatenAt >= :from)
			AND (:to IS NULL OR record.eatenAt < :to)
			AND (:storeId IS NULL OR record.store.id = :storeId)
			""")
	Slice<BreadRecord> findRecords(
			@Param("memberId") Long memberId,
			@Param("from") LocalDateTime from,
			@Param("to") LocalDateTime to,
			@Param("storeId") Long storeId,
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
