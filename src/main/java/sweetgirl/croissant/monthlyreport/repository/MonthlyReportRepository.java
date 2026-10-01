package sweetgirl.croissant.monthlyreport.repository;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReportMember, MonthlyReportMemberId> {

	@Query("""
			SELECT r.id.yearMonth AS yearMonth, r.publishedAt AS publishedAt
			FROM MonthlyReportMember r
			WHERE r.id.memberId = :memberId
				AND r.status = sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus.PUBLISHED
			ORDER BY r.id.yearMonth DESC
			""")
	Slice<PublishedReport> findPublished(@Param("memberId") Long memberId, Pageable pageable);

	/** 중복 발행은 DB에서 막는다. native 변경 후 영속성 컨텍스트에 남은 보류 상태도 비운다. */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			INSERT INTO monthly_report_member
			(member_id, year_month, status, payload, raw_metrics,
			calculation_version, generated_at, published_at)
			VALUES (:memberId, :yearMonth, 'PUBLISHED', CAST(:payload AS jsonb), CAST(:rawMetrics AS jsonb),
			:calculationVersion, :generatedAt, :publishedAt)
			ON CONFLICT (member_id, year_month) DO UPDATE
			SET status = 'PUBLISHED', payload = EXCLUDED.payload, raw_metrics = EXCLUDED.raw_metrics,
			calculation_version = EXCLUDED.calculation_version, generated_at = EXCLUDED.generated_at,
			published_at = EXCLUDED.published_at
			WHERE monthly_report_member.status <> 'PUBLISHED'
			""", nativeQuery = true)
	int publish(@Param("memberId") Long memberId, @Param("yearMonth") String yearMonth,
			@Param("payload") String payload, @Param("rawMetrics") String rawMetrics,
			@Param("calculationVersion") String calculationVersion, @Param("generatedAt") Instant generatedAt,
			@Param("publishedAt") Instant publishedAt);

	/** 삭제된 사진이나 다른 회원의 사진 키를 반환하지 않도록 소유권을 확인한다. */
	default Set<String> findVisiblePhotoKeys(Long memberId, Set<String> keys) {
		if (keys.isEmpty()) {
			return Set.of();
		}
		Set<String> visible = new HashSet<>();
		for (PhotoKeys photo : findOwnedPhotos(memberId, keys)) {
			for (String key : new String[] {photo.getAwsKey(), photo.getResizedKey320(),
				photo.getResizedKey640(), photo.getResizedKey1024()}) {
				if (key != null && keys.contains(key)) {
					visible.add(key);
				}
			}
		}
		return visible;
	}

	@Query(value = """
			SELECT p.aws_key AS "awsKey", p.resized_key_320 AS "resizedKey320",
				p.resized_key_640 AS "resizedKey640", p.resized_key_1024 AS "resizedKey1024"
			FROM bread_record_photo p JOIN bread_record r ON r.id = p.record_id
			WHERE r.member_id = :memberId AND r.deleted_at IS NULL
				AND (p.aws_key IN (:keys) OR p.resized_key_320 IN (:keys)
					OR p.resized_key_640 IN (:keys) OR p.resized_key_1024 IN (:keys))
			""", nativeQuery = true)
	List<PhotoKeys> findOwnedPhotos(@Param("memberId") Long memberId, @Param("keys") Set<String> keys);

	interface PublishedReport {
		String getYearMonth();

		Instant getPublishedAt();
	}

	interface PhotoKeys {
		String getAwsKey();

		String getResizedKey320();

		String getResizedKey640();

		String getResizedKey1024();
	}
}
