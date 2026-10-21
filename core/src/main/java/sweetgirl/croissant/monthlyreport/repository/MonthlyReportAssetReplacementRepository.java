package sweetgirl.croissant.monthlyreport.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportAssetReplacement;

public interface MonthlyReportAssetReplacementRepository extends JpaRepository<MonthlyReportAssetReplacement, String> {
	@Modifying
	@Query(value = """
			UPDATE monthly_report_asset_replacement SET image_key = :newKey, updated_at = CURRENT_TIMESTAMP
			WHERE image_key = :oldKey
			""", nativeQuery = true)
	int forwardReplacements(@Param("oldKey") String oldKey, @Param("newKey") String newKey);
}
