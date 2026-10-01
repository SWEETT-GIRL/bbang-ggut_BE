package sweetgirl.croissant.monthlyreport.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class MonthlyReportRepository {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public Optional<Snapshot> find(Long memberId, String yearMonth) {
		return jdbcTemplate.query("""
				SELECT status, payload::text AS payload
				FROM monthly_report_member
				WHERE member_id = :memberId AND year_month = :yearMonth
				""", Map.of("memberId", memberId, "yearMonth", yearMonth),
			(rs, rowNum) -> new Snapshot(rs.getString("status"), rs.getString("payload"))).stream().findFirst();
	}

	/** 최초 발행만 성공한다. 동시 실행/재시도는 기존 발행본과 발행 시각을 바꾸지 않는다. */
	public boolean publish(Long memberId, String yearMonth, String payload, String rawMetrics,
			String calculationVersion, Instant generatedAt, Instant publishedAt) {
		return jdbcTemplate.update(
			"""
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
					""",
			new MapSqlParameterSource("memberId", memberId)
				.addValue("yearMonth", yearMonth).addValue("payload", payload).addValue("rawMetrics", rawMetrics)
				.addValue("calculationVersion", calculationVersion).addValue("generatedAt", Timestamp.from(generatedAt))
				.addValue("publishedAt", Timestamp.from(publishedAt))) == 1;
	}

	/** 삭제된 사진이나 다른 회원의 사진 키를 반환하지 않도록 현재 소유권만 확인한다. */
	public Set<String> findVisiblePhotoKeys(Long memberId, Set<String> keys) {
		if (keys.isEmpty()) {
			return Set.of();
		}
		List<Map<String, Object>> photos = jdbcTemplate.queryForList("""
				SELECT p.aws_key, p.resized_key_320, p.resized_key_640, p.resized_key_1024
				FROM bread_record_photo p JOIN bread_record r ON r.id = p.record_id
				WHERE r.member_id = :memberId AND r.deleted_at IS NULL
					AND (p.aws_key IN (:keys) OR p.resized_key_320 IN (:keys)
						OR p.resized_key_640 IN (:keys) OR p.resized_key_1024 IN (:keys))
				""", Map.of("memberId", memberId, "keys", keys));
		Set<String> visible = new HashSet<>();
		for (Map<String, Object> photo : photos) {
			for (Object value : photo.values()) {
				if (value instanceof String key && keys.contains(key)) {
					visible.add(key);
				}
			}
		}
		return visible;
	}

	public record Snapshot(String status, String payload) {
	}
}
