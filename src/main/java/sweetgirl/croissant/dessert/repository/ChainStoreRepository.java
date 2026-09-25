package sweetgirl.croissant.dessert.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.dessert.dto.projection.DessertStoreProjection;
import sweetgirl.croissant.dessert.entity.ChainStore;

public interface ChainStoreRepository extends JpaRepository<ChainStore, Long> {

	/**
	 * 이 브랜드의 가까운 지점. ConvenienceStoreRepository 와 같은 모양으로 답한다 —
	 * 둘 다 "브랜드 이름으로 지점을 찾는" 같은 일이고, 표만 다르다.
	 *
	 * <p>브랜드 한정 메뉴는 "이 지점이 지금 파는지"를 따로 알지 못한다(초코츄러스처럼 품절 대란이면
	 * 더 그렇다). 그래서 등급·근거 자리를 비워 두고, 화면이 그 빈 자리를 보고 "파리바게뜨 매장"이라고
	 * 적는다 — "여기서 판다"고 단정하지 않는다.
	 */
	@Query(value = """
			SELECT
			CAST(NULL AS BIGINT) AS "storeId",
			chain.branch_name AS "name",
			chain.address AS "address",
			ST_Y(chain.location::geometry) AS "latitude",
			ST_X(chain.location::geometry) AS "longitude",
			ST_Distance(chain.location,
				ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS "distanceMeters",
			CAST(NULL AS VARCHAR) AS "grade",
			CAST(NULL AS INTEGER) AS "evidenceCount",
			CAST(NULL AS DATE) AS "lastEvidenceAt"
			FROM chain_store chain
			WHERE chain.brand = :brand
			AND ST_DWithin(chain.location,
				ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radiusMeters)
			ORDER BY "distanceMeters" ASC
			""", nativeQuery = true)
	List<DessertStoreProjection> findBranchesNearby(
			@Param("brand") String brand,
			@Param("lat") double latitude,
			@Param("lng") double longitude,
			@Param("radiusMeters") double radiusMeters,
			Pageable pageable);

	/**
	 * 이 브랜드의 지도 영역(사각형) 안 지점, 기준점에서 가까운 순. 모양은 findBranchesNearby 와 같다.
	 */
	@Query(value = """
			SELECT
			CAST(NULL AS BIGINT) AS "storeId",
			chain.branch_name AS "name",
			chain.address AS "address",
			ST_Y(chain.location::geometry) AS "latitude",
			ST_X(chain.location::geometry) AS "longitude",
			ST_Distance(chain.location,
				ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) AS "distanceMeters",
			CAST(NULL AS VARCHAR) AS "grade",
			CAST(NULL AS INTEGER) AS "evidenceCount",
			CAST(NULL AS DATE) AS "lastEvidenceAt"
			FROM chain_store chain
			WHERE chain.brand = :brand
			AND chain.location && ST_MakeEnvelope(:swLng, :swLat, :neLng, :neLat, 4326)::geography
			AND ST_Y(chain.location::geometry) BETWEEN :swLat AND :neLat
			AND ST_X(chain.location::geometry) BETWEEN :swLng AND :neLng
			ORDER BY "distanceMeters" ASC
			""", nativeQuery = true)
	List<DessertStoreProjection> findBranchesInBounds(
			@Param("brand") String brand,
			@Param("swLat") double swLat,
			@Param("swLng") double swLng,
			@Param("neLat") double neLat,
			@Param("neLng") double neLng,
			@Param("lat") double latitude,
			@Param("lng") double longitude,
			Pageable pageable);
}
