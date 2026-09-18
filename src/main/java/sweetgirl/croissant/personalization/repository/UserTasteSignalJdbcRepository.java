package sweetgirl.croissant.personalization.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.InteractionType;

/**
 * 프로필 계산의 읽기 쪽 (#663 ②). 한 회원의 창 안 신호를 store_profile 축·태그, 종류 이름과 함께 한 번에 읽는다 — "한 사람의 최근
 * N일" 한 가지 모양이라 idx_user_store_interaction_member_occurred 를 탄다. 쓰기는 JPA(UserTasteProfile)가 한다.
 */
@Repository
@RequiredArgsConstructor
public class UserTasteSignalJdbcRepository {

	private static final ObjectMapper TAGS_MAPPER = new ObjectMapper();
	private static final MapType TAGS_TYPE = TAGS_MAPPER.getTypeFactory().constructMapType(Map.class, String.class,
		Double.class);

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/** 인기도 · 적재 배치와 같은 방식 — 같은 트랜잭션 안에서 잡고, 커밋/롤백 때 풀린다. */
	public boolean tryLock(long lockKey) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(:lockKey)",
			new MapSqlParameterSource("lockKey", lockKey), Boolean.class));
	}

	/**
	 * 재계산 대상 = 창 안에 신호가 있는 회원 ∪ 프로필이 이미 있는 회원. 뒤쪽이 있어야 신호가 창 밖으로 빠진 회원의 축이 검사만으로
	 * 되돌아간다.
	 */
	public List<Long> findMemberIdsToRecompute(LocalDateTime since) {
		return jdbcTemplate.queryForList("""
				SELECT member_id FROM user_taste_profile
				UNION
				SELECT DISTINCT member_id FROM user_store_interaction WHERE occurred_at >= :since
				ORDER BY member_id
				""", new MapSqlParameterSource("since", since), Long.class);
	}

	/** 한 회원의 [since, ∞) 신호를 시각 순으로. 가게 프로필·종류는 LEFT JOIN 이라 없으면 null. */
	public List<TasteSignal> findSignals(Long memberId, LocalDateTime since) {
		return jdbcTemplate.query("""
				SELECT i.type, i.store_id, i.menu_id, i.converted, i.occurred_at,
					p.axis_taste, p.axis_texture, p.axis_filling, p.axis_vibe, CAST(p.tags AS text) AS tags,
					mc.name AS kind
				FROM user_store_interaction i
					LEFT JOIN store_profile p ON p.store_id = i.store_id
					LEFT JOIN menu m ON m.id = i.menu_id
					LEFT JOIN menu_category mc ON mc.id = m.menu_category_id
				WHERE i.member_id = :memberId AND i.occurred_at >= :since
				ORDER BY i.occurred_at, i.id
				""", new MapSqlParameterSource("memberId", memberId).addValue("since", since), this::toSignal);
	}

	/** 사용자 유형 DOER 의 재료 — 방문 체크는 user_store_interaction 에 없어서 따로 센다. */
	public int countVisits(Long memberId, LocalDateTime since) {
		Integer count = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM course_store_visits
				WHERE member_id = :memberId AND deleted_at IS NULL AND visited_at >= :since
				""", new MapSqlParameterSource("memberId", memberId).addValue("since", since), Integer.class);
		return count != null ? count : 0;
	}

	private TasteSignal toSignal(ResultSet rs, int rowNum) throws SQLException {
		BigDecimal taste = rs.getBigDecimal("axis_taste");
		TasteAxis storeAxis = taste == null ? null : TasteAxis.of(taste, rs.getBigDecimal("axis_texture"),
			rs.getBigDecimal("axis_filling"), rs.getBigDecimal("axis_vibe"));
		Long storeId = rs.getObject("store_id", Long.class);
		Long menuId = rs.getObject("menu_id", Long.class);
		Boolean converted = rs.getObject("converted", Boolean.class);
		return new TasteSignal(InteractionType.valueOf(rs.getString("type")), storeId, menuId, converted,
			rs.getTimestamp("occurred_at").toLocalDateTime(), storeAxis, parseTags(rs.getString("tags")),
			rs.getString("kind"));
	}

	private Map<String, Double> parseTags(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		try {
			return TAGS_MAPPER.readValue(json, TAGS_TYPE);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("store_profile.tags 가 {키: 숫자} 꼴이 아니다: " + json, e);
		}
	}
}
