package sweetgirl.croissant.recommendation.repository;

import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class PolygonAreaBindingRepository {
	private final NamedParameterJdbcTemplate jdbcTemplate;

	public Optional<Long> findReadyBinding(String versionKey, Long areaId) {
		return jdbcTemplate.queryForList("""
				SELECT b.id FROM course_area_region_bindings b
				JOIN course_area a ON a.id=b.course_area_id
					AND b.area_signature=jsonb_build_array(a.sido,a.passport_spot,a.region_2nd,a.region_3rd)
				JOIN region_boundary_versions v ON v.id=b.version_id AND v.ready
				WHERE v.version_key=:version AND b.course_area_id=:areaId
				""", Map.of("version", versionKey, "areaId", areaId), Long.class).stream().findFirst();
	}
}
