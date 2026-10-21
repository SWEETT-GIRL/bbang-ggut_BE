package sweetgirl.dataops.serving.config;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영 스키마 드리프트 가드 — ServingReader/Writer 가 참조하는 테이블·컬럼을 기동 시
 * information_schema 와 대조하고 불일치면 기동을 실패시킨다 (fail-fast).
 * 분리 레포라 운영 스키마 변경을 컴파일 타임에 잡을 수 없는 약점의 보완책 (repo-strategy-comparison.md §5).
 */
@Slf4j
@RequiredArgsConstructor
public class ProdSchemaGuard {

	static final Map<String, List<String>> REQUIRED_COLUMNS = Map.of(
		"store", List.of("id", "name", "address", "phone", "status", "naver_place_id", "instagram_handle", "category",
			"location", "region_1st", "region_2nd", "region_3rd", "updated_at"),
		"menu", List.of("id", "store_id", "name", "normalized_name", "price", "description", "deleted_at",
			"like_count", "menu_category_id", "created_at", "updated_at", "created_by_member_id"),
		"menu_category", List.of("id", "name"),
		// 개명 시 구명 보존 — updateStoreName 의 CTE 가 같은 문장에서 쓴다
		"store_name_alias", List.of("id", "store_id", "alias", "created_at"),
		"store_business_hour", List.of("id", "store_id", "day_of_week", "open_time", "close_time", "is_closed"),
		"contribution_report", List.of("id", "report_type", "status"),
		"menu_contribution_report",
		List.of("id", "contribution_report_id", "store_id", "menu_id", "name", "status"));

	private final JdbcTemplate prodJdbcTemplate;

	public void verify() {
		Map<String, Set<String>> actualColumns = loadActualColumns();
		List<String> missing = REQUIRED_COLUMNS.entrySet().stream()
			.flatMap(entry -> entry.getValue().stream()
				.filter(column -> !actualColumns.getOrDefault(entry.getKey(), Set.of()).contains(column))
				.map(column -> entry.getKey() + "." + column))
			.sorted()
			.toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException(
				"운영 DB 스키마 불일치 — 참조 컬럼이 없습니다: " + String.join(", ", missing)
					+ " (본 앱 스키마 변경 여부를 확인하고 ServingReader/ProdSchemaGuard 를 갱신하세요)");
		}
		log.info("운영 스키마 검증 통과 — 테이블 {}개 컬럼 {}개",
			REQUIRED_COLUMNS.size(),
			REQUIRED_COLUMNS.values().stream().mapToInt(List::size).sum());
	}

	private Map<String, Set<String>> loadActualColumns() {
		String placeholders = String.join(",", REQUIRED_COLUMNS.keySet().stream().map(t -> "?").toList());
		Map<String, Set<String>> actualColumns = new HashMap<>();
		prodJdbcTemplate.query(
			"SELECT table_name, column_name FROM information_schema.columns "
				+ "WHERE table_schema = 'public' AND table_name IN (" + placeholders + ")",
			resultSet -> {
				actualColumns
					.computeIfAbsent(resultSet.getString("table_name"), key -> new HashSet<>())
					.add(resultSet.getString("column_name"));
			},
			REQUIRED_COLUMNS.keySet().toArray());
		return actualColumns;
	}
}
