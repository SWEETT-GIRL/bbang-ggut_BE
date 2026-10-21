package sweetgirl.croissant.breadranking.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 빵집 랭킹 집계 — 표(rank = 1)와 태그 평가를 가게·카테고리 단위로 센다. 전부 PG 네이티브(FILTER·윈도) 쿼리 한 번씩이다.
 *
 * <p>탈퇴 회원(member.deleted_at)·삭제 가게(store.status)는 센 뒤에 빼는 게 아니라 조인에서 뺀다.
 * 태그 ↔ 카테고리는 태그의 breadCategory(MenuKind 이름)로 잇고, 룰이 빵·기타로 물러선 태그는 메뉴의 분류로 잇는다
 * ({@code BreadRecordTag.resolvedCategory()} 와 같은 규칙).
 */
@Repository
@RequiredArgsConstructor
public class BreadBoardJdbcRepository {

	/** 한 가게의 태그를 카테고리에 붙인 것 — 가게 상세 집계 세 쿼리가 같이 쓴다 */
	private static final String STORE_TAGS = """
			WITH tags AS (
				SELECT c.id AS category_id, t.id AS tag_id, r.member_id, t.again, t.menu_id
				FROM bread_record_tag t
				JOIN bread_record r ON r.id = t.record_id AND r.deleted_at IS NULL AND r.store_id = :storeId
				JOIN member m ON m.id = r.member_id AND m.deleted_at IS NULL
				LEFT JOIN menu mn ON mn.id = t.menu_id
				LEFT JOIN menu_category mc ON mc.id = mn.menu_category_id
				JOIN bread_category c ON c.menu_kind = CASE WHEN t.bread_category IN ('빵', '기타') THEN mc.name
					ELSE t.bread_category END
			)
			""";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	/**
	 * 한 카테고리의 가게 순위 — 표 수 → 또 먹을래요 % → 가장 최근 표 시각 → store_id. 공동 순위 없음.
	 * {@code totalVotes} 는 LIMIT 과 무관한 카테고리 전체 표 수(최소 표 판정용). 사용자 생성 카테고리는 kindName 이 비어 평가가 안 붙는다.
	 */
	public List<BoardRow> findBoard(long categoryId, String kindName, int limit) {
		return jdbcTemplate.query("""
				WITH votes AS (
					SELECT e.store_id, COUNT(*) AS votes, MAX(e.ranked_at) AS latest_vote_at
					FROM bread_rank_entry e
					JOIN member m ON m.id = e.member_id AND m.deleted_at IS NULL AND m.member_type = 'USER'
					JOIN store s ON s.id = e.store_id AND s.status = 'ACTIVE'
					WHERE e.category_id = :categoryId AND e.rank = 1
					GROUP BY e.store_id
				), tags AS (
					SELECT r.store_id, t.id AS tag_id, t.again
					FROM bread_record_tag t
					JOIN bread_record r ON r.id = t.record_id AND r.deleted_at IS NULL
					JOIN member m ON m.id = r.member_id AND m.deleted_at IS NULL
					LEFT JOIN menu mn ON mn.id = t.menu_id
					LEFT JOIN menu_category mc ON mc.id = mn.menu_category_id
					WHERE r.store_id IN (SELECT store_id FROM votes)
						AND (t.bread_category = :kindName
							OR (t.bread_category IN ('빵', '기타') AND mc.name = :kindName))
				), again AS (
					SELECT store_id,
						COUNT(*) FILTER (WHERE again) AS again_yes,
						COUNT(*) FILTER (WHERE again IS NOT NULL) AS again_total
					FROM tags GROUP BY store_id
				), texture AS (
					SELECT tg.store_id, c.chip,
						ROW_NUMBER() OVER (PARTITION BY tg.store_id ORDER BY COUNT(*) DESC, c.chip ASC) AS rn
					FROM tags tg JOIN bread_record_tag_chip c ON c.tag_id = tg.tag_id AND c.chip_type = 'TEXTURE'
					GROUP BY tg.store_id, c.chip
				)
				SELECT v.store_id, v.votes, v.latest_vote_at,
					COALESCE(a.again_yes, 0) AS again_yes, COALESCE(a.again_total, 0) AS again_total,
					tx.chip AS top_texture,
					SUM(v.votes) OVER () AS total_votes
				FROM votes v
				LEFT JOIN again a ON a.store_id = v.store_id
				LEFT JOIN texture tx ON tx.store_id = v.store_id AND tx.rn = 1
				ORDER BY v.votes DESC,
					(CASE WHEN a.again_total > 0 THEN a.again_yes::float8 / a.again_total END) DESC NULLS LAST,
					v.latest_vote_at DESC NULLS LAST, v.store_id ASC
				LIMIT :limit
				""",
			new MapSqlParameterSource()
				.addValue("categoryId", categoryId)
				.addValue("kindName", kindName == null ? "" : kindName)
				.addValue("limit", limit),
			(resultSet, index) -> new BoardRow(
				resultSet.getLong("store_id"),
				resultSet.getLong("votes"),
				resultSet.getObject("latest_vote_at", LocalDateTime.class),
				resultSet.getLong("again_yes"),
				resultSet.getLong("again_total"),
				resultSet.getString("top_texture"),
				resultSet.getLong("total_votes")));
	}

	/** 한 카테고리에서 1등을 매긴 서로 다른 회원 수(탈퇴·삭제 가게 제외). */
	public long countVoters(long categoryId) {
		Long voters = jdbcTemplate.queryForObject("""
				SELECT COUNT(DISTINCT e.member_id)
				FROM bread_rank_entry e
				JOIN member m ON m.id = e.member_id AND m.deleted_at IS NULL AND m.member_type = 'USER'
				JOIN store s ON s.id = e.store_id AND s.status = 'ACTIVE'
				WHERE e.category_id = :categoryId AND e.rank = 1
				""", new MapSqlParameterSource("categoryId", categoryId), Long.class);
		return voters == null ? 0 : voters;
	}

	/** 1등을 매긴 서로 다른 회원 수가 minVoters 이상인 카테고리 — 사람 수 많은 순. 탭·스냅샷 대상. */
	public List<CategoryVotersRow> findCategoriesByVoters(int minVoters) {
		return jdbcTemplate.query("""
				SELECT e.category_id, COUNT(DISTINCT e.member_id) AS voters, COUNT(*) AS votes
				FROM bread_rank_entry e
				JOIN member m ON m.id = e.member_id AND m.deleted_at IS NULL AND m.member_type = 'USER'
				JOIN store s ON s.id = e.store_id AND s.status = 'ACTIVE'
				WHERE e.rank = 1
				GROUP BY e.category_id
				HAVING COUNT(DISTINCT e.member_id) >= :minVoters
				ORDER BY voters DESC, votes DESC, e.category_id ASC
				""",
			new MapSqlParameterSource("minVoters", minVoters),
			(resultSet, index) -> new CategoryVotersRow(
				resultSet.getLong("category_id"), resultSet.getLong("voters"), resultSet.getLong("votes")));
	}

	/** 한 가게의 카테고리별 평가 — 평가한 사람(태그 단 회원) 수가 minRaters 이상인 것만. */
	public List<StoreCategoryRow> findStoreCategoryStats(long storeId, int minRaters) {
		return jdbcTemplate.query(STORE_TAGS + """
				SELECT category_id, COUNT(DISTINCT member_id) AS raters,
					COUNT(*) FILTER (WHERE again) AS again_yes,
					COUNT(*) FILTER (WHERE again IS NOT NULL) AS again_total
				FROM tags
				GROUP BY category_id
				HAVING COUNT(DISTINCT member_id) >= :minRaters
				ORDER BY raters DESC, category_id ASC
				""",
			new MapSqlParameterSource().addValue("storeId", storeId).addValue("minRaters", minRaters),
			(resultSet, index) -> new StoreCategoryRow(
				resultSet.getLong("category_id"), resultSet.getLong("raters"),
				resultSet.getLong("again_yes"), resultSet.getLong("again_total")));
	}

	/** 한 가게의 카테고리별 식감 칩 수 — rated 는 식감을 하나라도 고른 태그 수(비율의 분모). */
	public List<TextureStatRow> findStoreTextureStats(long storeId, List<Long> categoryIds) {
		if (categoryIds.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.query(STORE_TAGS + """
				, chips AS (
					SELECT tg.category_id, tg.tag_id, c.chip
					FROM tags tg JOIN bread_record_tag_chip c ON c.tag_id = tg.tag_id AND c.chip_type = 'TEXTURE'
					WHERE tg.category_id IN (:categoryIds)
				)
				SELECT ch.category_id, ch.chip, COUNT(*) AS cnt,
					(SELECT COUNT(DISTINCT x.tag_id) FROM chips x WHERE x.category_id = ch.category_id) AS rated
				FROM chips ch
				GROUP BY ch.category_id, ch.chip
				ORDER BY ch.category_id ASC, cnt DESC, ch.chip ASC
				""",
			new MapSqlParameterSource().addValue("storeId", storeId).addValue("categoryIds", categoryIds),
			(resultSet, index) -> new TextureStatRow(
				resultSet.getLong("category_id"), resultSet.getString("chip"),
				resultSet.getLong("cnt"), resultSet.getLong("rated")));
	}

	/** 한 가게의 카테고리별 대표 메뉴(가장 많이 태그된 것)와 그 가격. 메뉴 없는 태그뿐이면 행이 없다. */
	public List<MenuPriceRow> findStoreMenuPrices(long storeId, List<Long> categoryIds) {
		if (categoryIds.isEmpty()) {
			return List.of();
		}
		return jdbcTemplate.query(STORE_TAGS + """
				, counted AS (
					SELECT tg.category_id, tg.menu_id, COUNT(*) AS cnt,
						ROW_NUMBER() OVER (PARTITION BY tg.category_id ORDER BY COUNT(*) DESC, tg.menu_id ASC) AS rn
					FROM tags tg
					WHERE tg.menu_id IS NOT NULL AND tg.category_id IN (:categoryIds)
					GROUP BY tg.category_id, tg.menu_id
				)
				SELECT ct.category_id, mn.id AS menu_id, mn.price
				FROM counted ct JOIN menu mn ON mn.id = ct.menu_id AND mn.deleted_at IS NULL
				WHERE ct.rn = 1
				""",
			new MapSqlParameterSource().addValue("storeId", storeId).addValue("categoryIds", categoryIds),
			(resultSet, index) -> new MenuPriceRow(
				resultSet.getLong("category_id"), resultSet.getLong("menu_id"), nullableInt(resultSet, "price")));
	}

	private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
		int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	/** @param totalVotes 카테고리 전체 표 수(LIMIT 과 무관) · @param topTexture 최다 식감 칩 이름, 없으면 null */
	public record BoardRow(
			long storeId,
			long votes,
			LocalDateTime latestVoteAt,
			long againYes,
			long againTotal,
			String topTexture,
			long totalVotes) {
	}

	public record CategoryVotersRow(long categoryId, long voters, long votes) {
	}

	public record StoreCategoryRow(long categoryId, long raters, long againYes, long againTotal) {
	}

	public record TextureStatRow(long categoryId, String chip, long count, long rated) {
	}

	public record MenuPriceRow(long categoryId, long menuId, Integer price) {
	}
}
