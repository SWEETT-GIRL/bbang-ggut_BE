package sweetgirl.croissant.personalization.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
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
		Object.class);

	private static final List<String> KNOWN_TYPES = Arrays.stream(InteractionType.values()).map(Enum::name).toList();

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/** 인기도 · 적재 배치와 같은 방식 — 같은 트랜잭션 안에서 잡고, 커밋/롤백 때 풀린다. */
	public boolean tryLock(long lockKey) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(:lockKey)",
			new MapSqlParameterSource("lockKey", lockKey), Boolean.class));
	}

	/**
	 * 재계산 대상 = (창 안에 신호가 있는 회원 ∪ 프로필이 이미 있는 회원) 중 탈퇴하지 않은 회원. 뒤쪽이 있어야 신호가 창 밖으로 빠진
	 * 회원의 축이 검사만으로 되돌아간다. 탈퇴·병합된 게스트의 신호 행은 남지만 프로필은 만들지 않는다(게스트 신호 이관은 #663 ④).
	 */
	public List<Long> findMemberIdsToRecompute(LocalDateTime since) {
		return jdbcTemplate.queryForList("""
				SELECT t.member_id
				FROM (
					SELECT member_id FROM user_taste_profile
					UNION
					SELECT DISTINCT member_id FROM user_store_interaction WHERE occurred_at >= :since
				) t
					JOIN member m ON m.id = t.member_id AND m.deleted_at IS NULL
				ORDER BY t.member_id
				""", new MapSqlParameterSource("since", since), Long.class);
	}

	/**
	 * 한 회원의 [since, ∞) 신호를 시각 순으로. 가게 프로필·종류는 LEFT JOIN 이라 없으면 null. 이 코드가 모르는 type(뒤 배포가 먼저
	 * 적재한 새 종류)은 걸러 그 회원의 계산이 영구히 실패하지 않게 한다.
	 */
	public List<TasteSignal> findSignals(Long memberId, LocalDateTime since) {
		return jdbcTemplate.query("""
				SELECT i.type, i.store_id, i.menu_id, i.converted, i.occurred_at,
					p.axis_taste, p.axis_texture, p.axis_filling, p.axis_vibe, CAST(p.tags AS text) AS tags,
					mc.name AS kind
				FROM user_store_interaction i
					LEFT JOIN store_profile p ON p.store_id = i.store_id
					LEFT JOIN menu m ON m.id = i.menu_id
					LEFT JOIN menu_category mc ON mc.id = m.menu_category_id
				WHERE i.member_id = :memberId AND i.occurred_at >= :since AND i.type IN (:types)
				ORDER BY i.occurred_at, i.id
				""", new MapSqlParameterSource("memberId", memberId).addValue("since", since)
			.addValue("types", KNOWN_TYPES), this::toSignal);
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

	/**
	 * 관대하게 읽는다 — 숫자 값만 남기고 null · 문자열 · 중첩은 버린다. dataops 가 넣는 데이터 품질에 검사 제출(사용자 경로)이 묶이지
	 * 않게 하려는 것이고, 태그 사전 밖의 키는 계산기가 어차피 버린다. 아예 JSON 객체가 아니면 그 회원의 계산만 실패한다.
	 */
	static Map<String, Double> parseTags(String json) {
		if (json == null || json.isBlank()) {
			return Map.of();
		}
		Map<String, Object> raw;
		try {
			raw = TAGS_MAPPER.readValue(json, TAGS_TYPE);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("store_profile.tags 가 JSON 객체가 아니다: " + json, e);
		}
		Map<String, Double> tags = new LinkedHashMap<>();
		raw.forEach((key, value) -> {
			if (value instanceof Number number) {
				tags.put(key, number.doubleValue());
			}
		});
		return tags;
	}
}
