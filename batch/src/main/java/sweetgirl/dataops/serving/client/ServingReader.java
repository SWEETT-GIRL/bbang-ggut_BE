package sweetgirl.dataops.serving.client;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.serving.dto.MatchedStore;
import sweetgirl.dataops.serving.dto.MenuCurrentValues;
import sweetgirl.dataops.serving.dto.StoreCurrentValues;
import sweetgirl.dataops.serving.dto.UserContributedMenu;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;

/**
 * 운영 DB 현재값 SELECT 전용 게이트웨이 — 읽기 쿼리만 존재한다.
 * 참조 테이블·컬럼은 기동 시 ProdSchemaGuard 가 검증한다.
 */
@RequiredArgsConstructor
public class ServingReader {

	private final JdbcTemplate prodJdbcTemplate;

	public long countActiveMatchedStores() {
		Long count = prodJdbcTemplate.queryForObject(
			"SELECT count(*) FROM store WHERE status = 'ACTIVE' AND naver_place_id IS NOT NULL",
			Long.class);
		return count != null ? count : 0L;
	}

	public List<MatchedStore> findActiveMatchedStores() {
		return prodJdbcTemplate.query(
			"SELECT id, naver_place_id FROM store WHERE status = 'ACTIVE' AND naver_place_id IS NOT NULL",
			(resultSet, rowNum) -> new MatchedStore(
				resultSet.getLong("id"),
				resultSet.getString("naver_place_id")));
	}

	/**
	 * 사용자 기여 메뉴(MENU_ADD 승인분) 식별 — provenance USER 소급의 입력.
	 * 제약: 본 앱의 메뉴 추가 승인은 생성된 menu 를 리포트에 역링크하지 않으므로(menu_id NULL)
	 * (store_id, name) 이름 매칭으로 폴백한다. 승인 후 이름이 바뀐 메뉴는 잡지 못한다 — 시딩 전 표본 검증 필요 (플랜 P-2).
	 */
	public List<UserContributedMenu> findUserContributedMenus() {
		return prodJdbcTemplate.query(
			"SELECT DISTINCT m.store_id, m.id AS menu_id "
				+ "FROM menu_contribution_report mcr "
				+ "JOIN contribution_report cr ON cr.id = mcr.contribution_report_id "
				+ "JOIN menu m ON (mcr.menu_id = m.id) "
				+ "OR (mcr.menu_id IS NULL AND m.store_id = mcr.store_id AND m.name = mcr.name) "
				+ "WHERE cr.report_type = 'ADD' AND mcr.status = 'ACCEPTED' AND m.deleted_at IS NULL",
			(resultSet, rowNum) -> new UserContributedMenu(
				resultSet.getLong("store_id"),
				resultSet.getLong("menu_id")));
	}

	/** 메뉴가 하나도 없는 매칭 가게 — 관찰 표본 우선 대상 (리서치 2.5: 최초 수집 누락이 최대 문제) */
	public List<Long> findMenuZeroMatchedStoreIds() {
		return prodJdbcTemplate.queryForList(
			"SELECT s.id FROM store s "
				+ "WHERE s.status = 'ACTIVE' AND s.naver_place_id IS NOT NULL "
				+ "AND NOT EXISTS (SELECT 1 FROM menu m WHERE m.store_id = s.id AND m.deleted_at IS NULL)",
			Long.class);
	}

	/** 선점 확인용 — 반영 직전 다시 조회해 그새 매칭된 가게를 덮지 않는다 */
	public boolean hasNaverPlaceId(long storeId) {
		Boolean matched = prodJdbcTemplate.queryForObject(
			"SELECT naver_place_id IS NOT NULL FROM store WHERE id = ?", Boolean.class, storeId);
		return Boolean.TRUE.equals(matched);
	}

	/**
	 * 신규 등록 선점 확인 — placeId 유니크 제약은 상태 무관이므로 조회도 상태 무관이어야 한다
	 * (ACTIVE 만 보면 DELETED 보유 행과 INSERT 가 충돌 — 2026-08-25 실측 4건).
	 * ACTIVE 면 중복 귀속, DELETED 면 재활성 대상.
	 */
	public Optional<StoreRef> findStoreByNaverPlaceId(String naverPlaceId) {
		List<StoreRef> refs = prodJdbcTemplate.query(
			"SELECT id, status FROM store WHERE naver_place_id = ? ORDER BY id LIMIT 1",
			(rs, n) -> new StoreRef(rs.getLong("id"), rs.getString("status")), naverPlaceId);
		return refs.stream().findFirst();
	}

	public record StoreRef(long id, String status) {
	}

	public Optional<StoreCurrentValues> findStore(long storeId) {
		List<StoreCurrentValues> results = prodJdbcTemplate.query(
			"SELECT id, name, address, phone, status, instagram_handle, "
				+ "ST_X(location::geometry) AS lon, ST_Y(location::geometry) AS lat "
				+ "FROM store WHERE id = ?",
			(resultSet, rowNum) -> new StoreCurrentValues(
				resultSet.getLong("id"),
				resultSet.getString("name"),
				resultSet.getString("address"),
				resultSet.getString("phone"),
				resultSet.getString("status"),
				resultSet.getString("instagram_handle"),
				resultSet.getObject("lon", Double.class),
				resultSet.getObject("lat", Double.class)),
			storeId);
		return results.stream().findFirst();
	}

	/** 영업시간 현재값 — 비교용 "HH:mm" 정규화 (time(0) 은 "HH:mm:ss" 로 온다) */
	public List<BusinessHourRow> findBusinessHours(long storeId) {
		return prodJdbcTemplate.query(
			"SELECT day_of_week, open_time, close_time, is_closed FROM store_business_hour WHERE store_id = ?",
			(resultSet, rowNum) -> new BusinessHourRow(
				resultSet.getString("day_of_week"),
				toHourMinute(resultSet.getString("open_time")),
				toHourMinute(resultSet.getString("close_time")),
				resultSet.getBoolean("is_closed")),
			storeId);
	}

	private String toHourMinute(String time) {
		return time != null && time.length() >= 5 ? time.substring(0, 5) : time;
	}

	public List<MenuCurrentValues> findMenus(long storeId) {
		return prodJdbcTemplate.query(
			"SELECT m.id, m.name, m.price, m.description, m.created_by_member_id IS NOT NULL AS user_added, "
				+ "m.menu_category_id, mc.name AS category "
				+ "FROM menu m LEFT JOIN menu_category mc ON mc.id = m.menu_category_id "
				+ "WHERE m.store_id = ? AND m.deleted_at IS NULL",
			(resultSet, rowNum) -> new MenuCurrentValues(
				resultSet.getLong("id"),
				resultSet.getString("name"),
				resultSet.getObject("price", Integer.class),
				resultSet.getString("description"),
				resultSet.getBoolean("user_added"),
				resultSet.getObject("menu_category_id", Long.class),
				resultSet.getString("category")),
			storeId);
	}
}
