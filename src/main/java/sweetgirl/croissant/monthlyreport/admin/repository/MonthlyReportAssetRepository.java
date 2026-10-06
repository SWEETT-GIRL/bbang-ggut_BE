package sweetgirl.croissant.monthlyreport.admin.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset;

public interface MonthlyReportAssetRepository extends JpaRepository<MonthlyReportAsset, String> {
	List<MonthlyReportAsset> findByAssetVersionOrderById(String version);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select asset from MonthlyReportAsset asset where asset.id = :id")
	Optional<MonthlyReportAsset> findForUpdate(@Param("id") String id);

	@Modifying
	@Query(value = """
			INSERT INTO monthly_report_asset (id, asset_version, kind, character_code, planet_code,
			variant, image_key) VALUES (:id, :version, :kind, :character, :planet, :variant, :key) ON
			CONFLICT (id) DO NOTHING
			""", nativeQuery = true)
	int register(@Param("id") String id, @Param("version") String version, @Param("kind") String kind,
			@Param("character") String character, @Param("planet") String planet, @Param("variant") String variant,
			@Param("key") String key);
}
