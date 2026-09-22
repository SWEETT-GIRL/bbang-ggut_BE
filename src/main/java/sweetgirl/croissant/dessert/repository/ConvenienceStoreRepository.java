package sweetgirl.croissant.dessert.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;
import sweetgirl.croissant.dessert.entity.ConvenienceStore;

public interface ConvenienceStoreRepository extends JpaRepository<ConvenienceStore, Long> {

	/**
	 * 이 브랜드의 가까운 지점.
	 *
	 * <p>편의점 상품은 브랜드 전체에서 팔아 "이 지점이 파는지"를 따로 알지 못한다. 그래서
	 * 등급·근거 자리는 비워 같은 모양으로 답한다 — 화면이 그 빈 자리를 보고 "GS25 매장"이라고 적는다.
	 */
	@Query(value = """
			SELECT
			CAST(NULL AS BIGINT) AS "storeId",
			convenience.branch_name AS "name",
			convenience.address AS "address",
			ST_Y(convenience.location::geometry) AS "latitude",
			ST_X(convenience.location::geometry) AS "longitude",
			ST_Distance(convenience.location,
				ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS "distanceMeters",
			CAST(NULL AS VARCHAR) AS "grade",
			CAST(NULL AS INTEGER) AS "evidenceCount",
			CAST(NULL AS DATE) AS "lastEvidenceAt"
			FROM convenience_store convenience
			WHERE convenience.brand = :brand
			AND ST_DWithin(convenience.location,
				ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)
			ORDER BY "distanceMeters" ASC
			""", nativeQuery = true)
	List<DessertStoreProjection> findBranchesNearby(
			@Param("brand") String brand,
			@Param("lat") double latitude,
			@Param("lng") double longitude,
			@Param("radiusMeters") double radiusMeters,
			Pageable pageable);
}
