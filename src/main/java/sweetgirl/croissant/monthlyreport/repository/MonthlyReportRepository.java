package sweetgirl.croissant.monthlyreport.repository;

import java.time.Instant;

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
			SELECT r.id.yearMonth AS yearMonth, r.publishedAt AS publishedAt,
				r.characterCode AS characterCode, r.characterName AS characterName,
				r.characterImageKey AS characterImageKey
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
			calculation_version, generated_at, published_at,
			character_code, character_name, character_image_key, planet_code, planet_name)
			VALUES (:memberId, :yearMonth, 'PUBLISHED', CAST(:payload AS jsonb), CAST(:rawMetrics AS jsonb),
			:calculationVersion, :generatedAt, :publishedAt,
			CAST(:payload AS jsonb)->'character'->>'code',
			CAST(:payload AS jsonb)->'character'->>'name',
			CAST(:payload AS jsonb)->'character'->>'imageKey',
			CAST(:payload AS jsonb)->'planet'->>'code',
			CAST(:payload AS jsonb)->'planet'->>'name')
			ON CONFLICT (member_id, year_month) DO UPDATE
			SET status = 'PUBLISHED', payload = EXCLUDED.payload, raw_metrics = EXCLUDED.raw_metrics,
			calculation_version = EXCLUDED.calculation_version, generated_at = EXCLUDED.generated_at,
			published_at = EXCLUDED.published_at,
			character_code = EXCLUDED.character_code, character_name = EXCLUDED.character_name,
			character_image_key = EXCLUDED.character_image_key,
			planet_code = EXCLUDED.planet_code, planet_name = EXCLUDED.planet_name
			WHERE monthly_report_member.status <> 'PUBLISHED'
			""", nativeQuery = true)
	int publish(@Param("memberId") Long memberId, @Param("yearMonth") String yearMonth,
			@Param("payload") String payload, @Param("rawMetrics") String rawMetrics,
			@Param("calculationVersion") String calculationVersion, @Param("generatedAt") Instant generatedAt,
			@Param("publishedAt") Instant publishedAt);

	@Modifying(flushAutomatically = true)
	@Query(value = """
			INSERT INTO monthly_report_member (member_id, year_month, status) VALUES (:memberId, :yearMonth, 'FAILED')
			ON CONFLICT (member_id, year_month) DO UPDATE SET status = 'FAILED'
			WHERE monthly_report_member.status <> 'PUBLISHED'
			""", nativeQuery = true)
	int recordGenerationFailure(@Param("memberId") Long memberId, @Param("yearMonth") String yearMonth);

	interface PublishedReport {
		String getYearMonth();

		Instant getPublishedAt();

		String getCharacterCode();

		String getCharacterName();

		String getCharacterImageKey();
	}

}
