package sweetgirl.croissant.store.region;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** PostGIS 지역 판정. 서비스 트랜잭션 안에서 가게를 잠근 뒤 조회 좌표와 함께 저장한다. */
@Repository
@RequiredArgsConstructor
public class StoreRegionResolutionRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public boolean isReady(String versionKey) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS(SELECT 1 FROM region_boundary_versions WHERE version_key=:version AND ready)
				""", Map.of("version", versionKey), Boolean.class));
	}

	/** 한 번에 정해진 수만 판정한다. 미매칭도 저장하므로 다음 배치가 같은 가게에 갇히지 않는다. */
	public int refreshChanged(String versionKey, int batchSize) {
		return jdbcTemplate.update(
			"""
					WITH version AS MATERIALIZED (
						SELECT id FROM region_boundary_versions WHERE version_key=:version AND ready
					), pending AS MATERIALIZED (
						SELECT s.id, s.location::geometry AS point, v.id AS version_id
						FROM store s CROSS JOIN version v
						LEFT JOIN store_region_resolutions r ON r.store_id=s.id AND r.version_id=v.id
						WHERE r.id IS NULL OR ST_AsEWKB(r.query_location)
					IS DISTINCT FROM ST_AsEWKB(s.location::geometry)
						ORDER BY s.id LIMIT :batchSize FOR UPDATE OF s SKIP LOCKED
					), matched AS (
						SELECT p.*, matches.legal_codes, matches.scope_codes
						FROM pending p CROSS JOIN LATERAL (
							SELECT coalesce(array_agg(DISTINCT b.legal_code::text
						ORDER BY b.legal_code::text),'{}'::text[]) legal_codes,
								coalesce(array_agg(DISTINCT b.scope_code::text
						ORDER BY b.scope_code::text),'{}'::text[]) scope_codes
							FROM region_emd_boundaries b
							WHERE b.version_id=p.version_id AND ST_Covers(b.geom,p.point)
						) matches
					) INSERT INTO store_region_resolutions
						(store_id,version_id,query_location,legal_codes,scope_codes,scope_code,legal_status)
					SELECT id,version_id,point,legal_codes,scope_codes,
						CASE WHEN cardinality(scope_codes)=1 THEN scope_codes[1] END,
						CASE WHEN point IS NULL OR ST_IsEmpty(point) THEN 'NO_POINT'
								WHEN cardinality(legal_codes)=0 THEN 'NO_MATCH'
								WHEN cardinality(legal_codes)=1 THEN 'RESOLVED' ELSE 'AMBIGUOUS' END
					FROM matched
					ON CONFLICT (store_id,version_id) DO UPDATE SET
						query_location=excluded.query_location,legal_codes=excluded.legal_codes,
						scope_codes=excluded.scope_codes,scope_code=excluded.scope_code,
						legal_status=excluded.legal_status,updated_at=now()
					""",
			Map.of("version", versionKey, "batchSize", batchSize));
	}

	/** 현재 좌표·준비된 버전에 맞는 소속만 반환한다. 적격/방문/노출 제외는 추천 조회에서 별도로 적용한다. */
	public List<Long> findCurrentStoreIds(String versionKey, String scopeCode) {
		return jdbcTemplate.queryForList("""
				SELECT r.store_id FROM store_region_resolutions r
				JOIN region_boundary_versions v ON v.id=r.version_id AND v.ready
				JOIN store s ON s.id=r.store_id
				WHERE v.version_key=:version AND r.scope_code=:scope
					AND ST_AsEWKB(r.query_location) IS NOT DISTINCT FROM ST_AsEWKB(s.location::geometry)
				ORDER BY r.store_id
				""", Map.of("version", versionKey, "scope", scopeCode), Long.class);
	}
}
