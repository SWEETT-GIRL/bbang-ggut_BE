package sweetgirl.dataops.serving.client;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.serving.dto.MenuUpsertResult;
import sweetgirl.dataops.snapshot.parser.BusinessHourRow;

/**
 * 운영 DB DML 의 유일한 위치 — 이 클래스 밖에 운영 쓰기 SQL 이 존재해서는 안 된다.
 *
 * 규율 (설계서 4.2 + v3 P1-1):
 * - 모든 메서드는 apply_log 선기록을 전제로 한다 (호출자인 Proposal/AutoApply 서비스가 선기록 후 호출)
 * - 물리 DELETE 메서드를 만들지 않는다 — soft delete 만. 예외: 영업시간 세트 교체(replaceBusinessHours)는
 *   요일 행의 원자적 재작성이 목적이라 같은 문장 안의 DELETE 를 허용한다
 * - DDL 금지 — 참조 컬럼은 기동 시 ProdSchemaGuard 가 검증한다
 * - 갱신은 조건부로만 쓴다: 제안 당시의 값(expected*)이 운영에 그대로일 때만 바꾸고, 반환 행 수로 확인한다.
 *   운영 값이 그새 바뀌었으면 0행 — 호출자가 CAS 충돌로 다루고 덮어쓰지 않는다.
 *   여기에 사용자 기여(menu.created_by_member_id)와 활성 상태(store.status, menu.deleted_at) 조건을 함께 건다
 * - 의미상 한 변경은 한 문장으로 쓴다 (개명+alias, 주소+좌표+지역) — prod 커넥션은 문장 단위 자동 커밋이라
 *   두 문장으로 나누면 중간 실패가 반쪽 반영을 남긴다
 */
@RequiredArgsConstructor
public class ServingWriter {

	private final JdbcTemplate prodJdbcTemplate;

	/** 살아 있는 가게만 — 폐업·중복 정리로 DELETED 된 행을 되살리는 갱신을 막는다 */
	private static final String STORE_ALIVE = " AND status <> 'DELETED'";

	/**
	 * 개명 — 구명(舊名)을 alias 로 보존하는 INSERT 까지 한 문장(CTE)으로 묶는다.
	 * pg_bigm 검색이 alias_normalized 를 타므로 개명 후에도 옛 이름 검색이 유지된다.
	 * UPDATE 가 0행이면(값이 그새 바뀜·DELETED) alias 도 넣지 않는다 — 반쪽 반영이 생기지 않는다.
	 *
	 * @param expectedName 제안 당시의 이름. 운영이 이 값일 때만 바꾼다
	 * @param preservedAlias alias 로 남길 구명. null 이면 alias 를 넣지 않는다
	 */
	public int updateStoreName(long storeId, String expectedName, String name, String preservedAlias) {
		Long updated = prodJdbcTemplate.queryForObject(
			"WITH updated AS ("
				+ "UPDATE store SET name = ?, updated_at = now() "
				+ "WHERE id = ? AND name IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE
				+ " RETURNING id), "
				+ "alias AS ("
				+ "INSERT INTO store_name_alias (store_id, alias, created_at) "
				+ "SELECT updated.id, CAST(? AS VARCHAR), now() FROM updated "
				+ "WHERE CAST(? AS VARCHAR) IS NOT NULL ON CONFLICT DO NOTHING) "
				+ "SELECT count(*) FROM updated",
			Long.class, name, storeId, expectedName, preservedAlias, preservedAlias);
		return updated == null ? 0 : updated.intValue();
	}

	public int updateStorePhone(long storeId, String expectedPhone, String phone) {
		return prodJdbcTemplate.update(
			"UPDATE store SET phone = ?, updated_at = now() "
				+ "WHERE id = ? AND phone IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			phone, storeId, expectedPhone);
	}

	/** Exact agent path also binds the current serving row to the approved external entity. */
	public int updateStoreName(long storeId, String expectedExternalId, String expectedName, String name, String preservedAlias) {
		Long updated = prodJdbcTemplate.queryForObject(
			"WITH updated AS (UPDATE store SET name = ?, updated_at = now() "
				+ "WHERE id = ? AND naver_place_id = ? AND name IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE
				+ " RETURNING id), alias AS (INSERT INTO store_name_alias(store_id,alias,created_at) "
				+ "SELECT updated.id,CAST(? AS VARCHAR),now() FROM updated WHERE CAST(? AS VARCHAR) IS NOT NULL ON CONFLICT DO NOTHING) "
				+ "SELECT count(*) FROM updated",Long.class,name,storeId,expectedExternalId,expectedName,preservedAlias,preservedAlias);
		return updated == null ? 0 : updated.intValue();
	}

	public int updateStorePhone(long storeId, String expectedExternalId, String expectedPhone, String phone) {
		return prodJdbcTemplate.update("UPDATE store SET phone = ?, updated_at = now() "
			+ "WHERE id = ? AND naver_place_id = ? AND phone IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			phone,storeId,expectedExternalId,expectedPhone);
	}

	/**
	 * 네이버 매칭 — 제안 당시 비어 있던(또는 그 값이던) 가게만 바꾼다. 매칭 선점 방지는 이 조건이 담당한다:
	 * 읽고 나서 쓰는 사이에 다른 경로가 매칭했으면 0행이다.
	 */
	public int updateStoreNaverPlaceId(long storeId, String expectedNaverPlaceId, String naverPlaceId) {
		return prodJdbcTemplate.update(
			"UPDATE store SET naver_place_id = ?, updated_at = now() "
				+ "WHERE id = ? AND naver_place_id IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			naverPlaceId, storeId, expectedNaverPlaceId);
	}

	public int updateStoreInstagram(long storeId, String expectedHandle, String instagramHandle) {
		return prodJdbcTemplate.update(
			"UPDATE store SET instagram_handle = ?, updated_at = now() "
				+ "WHERE id = ? AND instagram_handle IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			instagramHandle, storeId, expectedHandle);
	}

	public int updateStoreInstagram(long storeId, String expectedExternalId, String expectedHandle, String instagramHandle) {
		return prodJdbcTemplate.update("UPDATE store SET instagram_handle = ?, updated_at = now() "
			+ "WHERE id = ? AND naver_place_id = ? AND instagram_handle IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			instagramHandle,storeId,expectedExternalId,expectedHandle);
	}

	/**
	 * 지역(시도·시군구·읍면동) 갱신 — 값의 출처는 좌표 역산(카카오 B타입 법정동 → 앱 어휘), 주소 텍스트가 아니다 (이슈 #5).
	 * 세 컬럼이 제안 당시 값 그대로일 때만 바꾼다 — 한 컬럼만 바뀌어 있어도 다른 근거가 끼어든 것이다.
	 */
	public int updateStoreRegion(long storeId, String expected1st, String expected2nd, String expected3rd,
			String region1st, String region2nd, String region3rd) {
		return prodJdbcTemplate.update(
			"UPDATE store SET region_1st = ?, region_2nd = ?, region_3rd = ?, updated_at = now() "
				+ "WHERE id = ? AND region_1st IS NOT DISTINCT FROM CAST(? AS VARCHAR) "
				+ "AND region_2nd IS NOT DISTINCT FROM CAST(? AS VARCHAR) "
				+ "AND region_3rd IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			region1st, region2nd, region3rd, storeId, expected1st, expected2nd, expected3rd);
	}

	/**
	 * 가게 분류 갱신 — 값은 BE StoreCategory enum 의 이름(BAKERY, CAFE_DESSERT, … OTHER)이다 (BE #730).
	 * 네이버 카테고리 → 코드 매핑은 BE enum 이 정본이고 dataops 는 그 생성표(scripts/store_category_map.py)로 제안한다.
	 * 운영에 쓰려면 BE #730 이 배포돼 있어야 한다 — 옛 BE(enum WESTERN 하나)는 BAKERY 를 읽는 순간 JPA 가 터진다.
	 */
	public int updateStoreCategory(long storeId, String expectedCategory, String category) {
		return prodJdbcTemplate.update(
			"UPDATE store SET category = ?, updated_at = now() "
				+ "WHERE id = ? AND category IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			category, storeId, expectedCategory);
	}

	/**
	 * 주소·좌표·지역 갱신 — 한 문장으로 쓴다. 좌표는 지오펜싱(빵알람) 입력이고 지역은 좌표 역산 결과라
	 * 의미상 한 변경이다. 나눠 쓰면 중간 실패가 주소만 옮겨진 가게(좌표·지역은 옛 자리)를 남긴다.
	 * 좌표·지역은 값이 있을 때만 SET 에 들어간다 — null 로 덮지 않는다.
	 */
	public int updateStoreAddress(long storeId, String expectedAddress, String address,
			Double longitude, Double latitude, String region1st, String region2nd, String region3rd) {
		List<String> sets = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		sets.add("address = ?");
		params.add(address);
		if (longitude != null && latitude != null) {
			sets.add("location = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography");
			params.add(longitude);
			params.add(latitude);
		}
		if (region1st != null && region3rd != null) {
			sets.add("region_1st = ?");
			sets.add("region_2nd = ?");
			sets.add("region_3rd = ?");
			params.add(region1st);
			params.add(region2nd);
			params.add(region3rd);
		}
		sets.add("updated_at = now()");
		params.add(storeId);
		params.add(expectedAddress);
		return prodJdbcTemplate.update(
			"UPDATE store SET " + String.join(", ", sets)
				+ " WHERE id = ? AND address IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + STORE_ALIVE,
			params.toArray());
	}

	/**
	 * 영업시간 세트 교체 — 부모·기존 요일 행을 잠근 뒤 새 statement에서 기대 세트를 확인한다.
	 * 삭제·삽입은 같은 serving 트랜잭션에서 커밋한다. 활성 nondeferrable FK가 새 요일 삽입을
	 * 부모 잠금과 직렬화해야 하며, 해당 제약·잠금 권한이 없으면 쓰기 전에 보류한다.
	 * 수동 경로는 기존 store ID + live 권한을 유지한다. 외부 ID는 exact agent 경로에서 추가 검증한다.
	 *
	 * @return 넣은 요일 행 수 — 0 이면 빈 대체 세트이거나 운영 값·상태가 바뀌어 아무것도 쓰지 않았다
	 */
	public int replaceBusinessHours(long storeId, List<BusinessHourRow> expected, List<BusinessHourRow> rows) {
		if (rows.isEmpty()) return 0;
		return new ExactBusinessHoursWriter(prodJdbcTemplate).replaceManual(storeId,expected,rows);
	}

	/** Exact agent path requires a validated active FK and one private serving transaction. */
	public int replaceBusinessHours(long storeId,String expectedExternalId,List<BusinessHourRow> expected,List<BusinessHourRow> rows) {
		return new ExactBusinessHoursWriter(prodJdbcTemplate).replace(storeId,expectedExternalId,expected,rows);
	}

	/** 영업시간 세트의 비교 모양 — 요일 문자열 순(바이트 순), "요일|HH:MM|HH:MM|true/false" 를 ';' 로 잇는다. 빈 세트는 "" */
	static String hoursSignature(List<BusinessHourRow> rows) {
		return rows.stream()
			.sorted(java.util.Comparator.comparing(BusinessHourRow::dayOfWeek))
			.map(row -> row.dayOfWeek() + "|" + (row.openTime() != null ? row.openTime() : "") + "|"
				+ (row.closeTime() != null ? row.closeTime() : "") + "|" + row.closed())
			.collect(java.util.stream.Collectors.joining(";"));
	}

	/**
	 * 신규 가게 INSERT — 4단계 공공데이터 신규 유입의 등록 경로. 반환: 생성된 store id.
	 *
	 * 스키마 전제 (2026-08-21 운영 실측):
	 * - id 는 IDENTITY BY DEFAULT, name_normalized·address_normalized 는 생성 컬럼 — 공급하지 않는다
	 * - source 는 본 앱 enum(StoreSource) 값만 허용 — 신규 값 추가는 본 앱 배포가 선행돼야 하므로
	 *   ADMIN_REGISTERED 를 재사용한다 (dataops 등록 = 검증 게이트를 통과한 어드민 등록의 자동화,
	 *   출처 구분은 store_field_provenance 의 LOCALDATA 로 남는다)
	 * - category 는 운영 관행상 NULL (양식 검색 제외용 WESTERN 만 세팅돼 있음)
	 * - region_1st/2nd/3rd 는 호출자가 좌표의 법정동 근거(region_resolution)에서 투영한 값을 넘긴다 (v3 P4-4).
	 *   인허가 지번주소에서 3토큰을 잘라 쓰던 방식은 검증이 아니라 쓰지 않는다
	 */
	public Long insertStore(String name, String address, double longitude, double latitude, String phone,
			String region1st, String region2nd, String region3rd, String naverPlaceId) {
		return prodJdbcTemplate.queryForObject(INSERT_STORE, Long.class,
			name, address, longitude, latitude, phone, region1st, region2nd, region3rd, naverPlaceId);
	}

	/**
	 * 신규 가게 INSERT 시험 — {@link #insertStore} 와 같은 문장을 한 트랜잭션 안에서 실행하고 <b>반드시 롤백</b>한다.
	 * 운영 제약(NOT NULL·유니크·CHECK·트리거)을 실제로 통과하는지 쓰기 전에 확인하는 용도다. 반환: 롤백된 행의 id.
	 *
	 * 남는 흔적은 IDENTITY 시퀀스가 한 칸 넘어가는 것뿐이다(시퀀스는 롤백되지 않는다) — 실제 등록의 id 에 빈칸이 생긴다.
	 * prod 커넥션은 문장 단위 자동 커밋이라 여기서만 자동 커밋을 끄고, 끝나면 되돌린다.
	 */
	public Long probeInsertStore(String name, String address, double longitude, double latitude, String phone,
			String region1st, String region2nd, String region3rd, String naverPlaceId) {
		Object[] args = {name, address, longitude, latitude, phone, region1st, region2nd, region3rd, naverPlaceId};
		return prodJdbcTemplate.execute((ConnectionCallback<Long>)connection -> {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try (PreparedStatement statement = connection.prepareStatement(INSERT_STORE)) {
				new ArgumentPreparedStatementSetter(args).setValues(statement);
				try (ResultSet rs = statement.executeQuery()) {
					return rs.next() ? rs.getLong(1) : null;
				}
			} finally {
				connection.rollback();
				connection.setAutoCommit(autoCommit);
			}
		});
	}

	private static final String INSERT_STORE =
		"INSERT INTO store (name, address, location, phone, region_1st, region_2nd, region_3rd, "
			+ "naver_place_id, naver_matched_at, source, status, created_at, updated_at) "
			+ "VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?, ?, now(), "
			+ "'ADMIN_REGISTERED', 'ACTIVE', now(), now()) RETURNING id";

	/** 오삭제 정정·재개업 — DELETED 행을 이력 보존한 채 되살린다 (신규 INSERT 대신) */
	public int reactivateStore(long storeId) {
		return prodJdbcTemplate.update(
			"UPDATE store SET status = 'ACTIVE', updated_at = now() WHERE id = ? AND status = 'DELETED'",
			storeId);
	}

	/**
	 * 폐업·중복·서비스제외 정리 — 물리 삭제가 아니라 상태 전환.
	 * 제안 당시 상태(보통 ACTIVE)가 그대로일 때만 — 이미 DELETED 면 0행이고, 그새 다른 상태가 됐으면 사람이 다시 본다.
	 */
	public int softDeleteStore(long storeId, String expectedStatus) {
		return prodJdbcTemplate.update(
			"UPDATE store SET status = 'DELETED', updated_at = now() "
				+ "WHERE id = ? AND status IS NOT DISTINCT FROM CAST(? AS VARCHAR) AND status <> 'DELETED'",
			storeId, expectedStatus);
	}

	/** 신규 메뉴 — 백필·재수집의 신규 추가. 반환: 생성된 menu id */
	/**
	 * 메뉴 INSERT — normalized_name(NOT NULL) 은 운영 유니크 인덱스(uq_menu_store_normalized_name)와
	 * 동일한 식으로 계산한다. 카테고리(세분류 kind)는 이름→id 서브쿼리로 해석하고,
	 * categoryName 이 null(룰 매핑 실패)이거나 행이 없으면 menu_category_id 를 NULL 로 둔다 —
	 * 본 앱 BE 스케줄러가 NULL 인 메뉴를 주워 AI 로 분류한다 (2026-09-08. 그전엔 '기타' 폴백).
	 */
	public MenuUpsertResult upsertMenu(long storeId, String name, Integer price, String description,
			String categoryName) {
		return upsertMenu(storeId, name, price, description, categoryName, true);
	}

	/** A delivery-menu addition approval never authorizes updating a menu created in the meantime. */
	public MenuUpsertResult upsertMenu(long storeId, String name, Integer price, String description,
			String categoryName, boolean allowExistingUpdate) {
		// 1) 유니크 인덱스(uq_menu_store_normalized_name)와 같은 식으로 겹치는 살아 있는 메뉴를 먼저 찾는다.
		//    재판정(자바 \p{L}\p{N})과 운영([[:alnum:]])의 정규화가 한자·가나에서 갈려 "새 메뉴"로 제안된 것이
		//    여기서 잡힌다 (2026-09-06 실측: 일본어 병기 가게 6건 + 한 스냅샷 안 동명 17건).
		List<Object[]> existing = prodJdbcTemplate.query(
			"SELECT id, name, price, description, created_by_member_id IS NOT NULL AS user_menu FROM menu "
				+ "WHERE store_id = ? AND deleted_at IS NULL AND created_at >= '2026-05-27 00:00:00' "
				+ "AND lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g')) "
				+ "= lower(regexp_replace(?, '[^[:alnum:]]+', '', 'g')) "
				+ "ORDER BY id LIMIT 1",
			(resultSet, rowNum) -> new Object[] {
				resultSet.getLong("id"), resultSet.getString("name"), resultSet.getObject("price", Integer.class),
				resultSet.getString("description"), resultSet.getBoolean("user_menu")},
			storeId, name);
		if (!existing.isEmpty()) {
			Object[] row = existing.get(0);
			long menuId = (Long) row[0];
			if (!allowExistingUpdate) return MenuUpsertResult.blockedByExistingMenu(menuId);
			if (Boolean.TRUE.equals(row[4])) {
				return MenuUpsertResult.blockedByUserMenu(menuId);   // 사용자 등록 메뉴는 네이버 값으로 덮지 않는다
			}
			if (!sameName((String) row[1], name)) {
				// 답례세트① vs ③ 처럼 운영 키만 같은 별개 상품 — 덮어쓰면 다른 상품 가격이 된다 (09-06 실측)
				return MenuUpsertResult.blockedByDistinctName(menuId);
			}
			// 2) 이름까지 같으면 갱신 — 가격은 새 값이 있을 때만, 설명은 새 값이 비어 있지 않을 때만 (없던 값을 지우지 않음)
			prodJdbcTemplate.update(
				"UPDATE menu SET price = COALESCE(?, price), "
					+ "description = CASE WHEN COALESCE(?, '') <> '' THEN LEFT(?, 255) ELSE description END, "
					+ "updated_at = now() WHERE id = ?",
				price, description, description, menuId);
			return MenuUpsertResult.updated(menuId, (Integer) row[2], (String) row[3]);
		}
		// 3) 없으면 삽입 — description 은 varchar(255) 라 DB 에서 절단 (실측 33건)
		Long menuId = prodJdbcTemplate.queryForObject(
			"INSERT INTO menu (store_id, name, normalized_name, price, description, menu_category_id, "
				+ "like_count, created_at, updated_at) "
				+ "VALUES (?, ?, lower(regexp_replace(?, '[^[:alnum:]]+', '', 'g')), ?, LEFT(?, 255), "
				+ "(SELECT id FROM menu_category WHERE name = ?), 0, now(), now()) RETURNING id",
			Long.class, storeId, name, name, price, description, categoryName);
		return MenuUpsertResult.inserted(menuId);
	}

	/** Automatic additions never upsert a row that appeared after observation. */
	public MenuUpsertResult insertObservedMenu(long storeId, String externalId, String name, Integer price,
			String description, String categoryName) {
		List<Long> inserted = prodJdbcTemplate.queryForList("""
			WITH observed_store AS MATERIALIZED (
			    SELECT id FROM store WHERE id = ? AND naver_place_id = ? AND status = 'ACTIVE' FOR UPDATE
			)
			INSERT INTO menu (store_id, name, normalized_name, price, description, menu_category_id,
			    like_count, created_at, updated_at)
			SELECT s.id, ?, lower(regexp_replace(?, '[^[:alnum:]]+', '', 'g')), ?, LEFT(?, 255),
			    (SELECT id FROM menu_category WHERE name = ?), 0, now(), now()
			FROM observed_store s WHERE NOT EXISTS (
			    SELECT 1 FROM menu m WHERE m.store_id = s.id AND m.deleted_at IS NULL
			    AND lower(regexp_replace(m.name, '[^[:alnum:]]+', '', 'g'))
			        = lower(regexp_replace(?, '[^[:alnum:]]+', '', 'g'))
			) ON CONFLICT DO NOTHING RETURNING id
			""", Long.class, storeId, externalId, name, name, price, description, categoryName, name);
		return inserted.isEmpty() ? MenuUpsertResult.blockedByExistingMenu(null) : MenuUpsertResult.inserted(inserted.get(0));
	}

	public int updateObservedStoreAddress(long storeId, String externalId, String expectedAddress, double expectedLon,
			double expectedLat, String address, double lon, double lat, String region1st, String region2nd, String region3rd) {
		return prodJdbcTemplate.update("""
			UPDATE store SET address=?, location=ST_SetSRID(ST_MakePoint(?,?),4326)::geography,
			region_1st=?, region_2nd=?, region_3rd=?, updated_at=now()
			WHERE id=? AND naver_place_id=? AND status='ACTIVE' AND address IS NOT DISTINCT FROM CAST(? AS text)
			AND ST_X(location::geometry)=? AND ST_Y(location::geometry)=?
			""", address, lon, lat, region1st, region2nd, region3rd, storeId, externalId, expectedAddress, expectedLon, expectedLat);
	}

	public int updateObservedMenuCategory(long menuId, long storeId, String externalId, Long expectedCategoryId, String category) {
		return prodJdbcTemplate.update("""
			WITH observed_store AS MATERIALIZED (SELECT id FROM store WHERE id=? AND naver_place_id=? AND status='ACTIVE' FOR UPDATE)
			UPDATE menu m SET menu_category_id=c.id, updated_at=now() FROM menu_category c, observed_store s
			WHERE m.id=? AND m.store_id=s.id AND c.name=? AND m.menu_category_id IS NOT DISTINCT FROM CAST(? AS bigint)
			AND m.deleted_at IS NULL AND m.created_by_member_id IS NULL
			""", storeId, externalId, menuId, category, expectedCategoryId);
	}

	public int softDeleteObservedMenu(long menuId, long storeId, String externalId, String name, Integer price, String description) {
		return prodJdbcTemplate.update("""
			WITH observed_store AS MATERIALIZED (SELECT id FROM store WHERE id=? AND naver_place_id=? AND status='ACTIVE' FOR UPDATE)
			UPDATE menu m SET deleted_at=now(), updated_at=now() FROM observed_store s
			WHERE m.id=? AND m.store_id=s.id AND m.name IS NOT DISTINCT FROM CAST(? AS text)
			AND m.price IS NOT DISTINCT FROM CAST(? AS integer) AND m.description IS NOT DISTINCT FROM CAST(? AS text)
			AND m.deleted_at IS NULL AND m.created_by_member_id IS NULL
			""", storeId, externalId, menuId, name, price, description);
	}

	/** 재판정과 같은 유니코드 정규화(\p{L}\p{N}, 소문자) — 공백·기호·대소문자만 다른 이름은 같은 상품으로 본다 */
	static boolean sameName(String a, String b) {
		return normalizeUnicode(a).equals(normalizeUnicode(b));
	}

	private static String normalizeUnicode(String name) {
		return name == null ? "" : name.replaceAll("[^\\p{L}\\p{N}]+", "").toLowerCase();
	}

	/** 살아 있고 사용자가 만들지 않은 메뉴만 — 사용자 기여는 네이버 값으로 덮지 않는다 (upsertMenu 와 같은 규율) */
	private static final String MENU_WRITABLE = " AND deleted_at IS NULL AND created_by_member_id IS NULL";

	/** 가격 갱신 — 제안 당시 가격이 그대로일 때만. 어드민·사용자가 먼저 고쳤으면 0행 */
	public int updateMenuPrice(long menuId, Integer expectedPrice, Integer price) {
		return prodJdbcTemplate.update(
			"UPDATE menu SET price = ?, updated_at = now() "
				+ "WHERE id = ? AND price IS NOT DISTINCT FROM CAST(? AS INTEGER)" + MENU_WRITABLE,
			price, menuId, expectedPrice);
	}

	/** Agent exact manifest path: the approved store association must still match at the write. */
	public int updateMenuPrice(long menuId, long expectedStoreId, String expectedExternalId, Integer expectedPrice, Integer price) {
		return prodJdbcTemplate.update(
			"WITH approved_store AS MATERIALIZED (SELECT id FROM store "
				+ "WHERE id = ? AND naver_place_id = ? AND status <> 'DELETED' FOR UPDATE) "
				+ "UPDATE menu m SET price = ?, updated_at = now() FROM approved_store s "
				+ "WHERE m.id = ? AND m.store_id = s.id AND price IS NOT DISTINCT FROM CAST(? AS INTEGER)" + MENU_WRITABLE,
			expectedStoreId,expectedExternalId,price,menuId,expectedPrice);
	}

	/** 메뉴 설명 갱신 — 사용자 등록 메뉴(created_by_member_id)는 건드리지 않는다 (갱신 0행) */
	public int updateMenuDescription(long menuId, String expectedDescription, String description) {
		return prodJdbcTemplate.update(
			"UPDATE menu SET description = LEFT(?, 255), updated_at = now() "
				+ "WHERE id = ? AND description IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + MENU_WRITABLE,
			description, menuId, expectedDescription);
	}

	/** Agent input is already bounded to 255 characters; write the exact approved text without truncation. */
	public int updateMenuDescription(long menuId, long expectedStoreId, String expectedExternalId, String expectedDescription, String description) {
		return prodJdbcTemplate.update(
			"WITH approved_store AS MATERIALIZED (SELECT id FROM store "
				+ "WHERE id = ? AND naver_place_id = ? AND status <> 'DELETED' FOR UPDATE) "
				+ "UPDATE menu m SET description = ?, updated_at = now() FROM approved_store s "
				+ "WHERE m.id = ? AND m.store_id = s.id AND description IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + MENU_WRITABLE,
			expectedStoreId,expectedExternalId,description,menuId,expectedDescription);
	}

	/**
	 * 메뉴 종류 재분류 (정책 재판정 menu.{id}.category) — 제안 당시 종류(expectedCategoryId, NULL 포함)가 그대로일 때만
	 * 바꾼다(compare-and-set). 그새 AI 스케줄러·어드민이 바꿨거나, 지워졌거나, 사용자 등록 메뉴거나, 대상 종류가
	 * menu_category 에 없으면 0행 — 호출자가 반영 실패로 다룬다.
	 */
	public int updateMenuCategory(long menuId, Long expectedCategoryId, String categoryName) {
		return prodJdbcTemplate.update(
			"UPDATE menu m SET menu_category_id = c.id, updated_at = now() FROM menu_category c "
				+ "WHERE c.name = ? AND m.id = ? AND m.deleted_at IS NULL AND m.created_by_member_id IS NULL "
				+ "AND m.menu_category_id IS NOT DISTINCT FROM CAST(? AS BIGINT)",
			categoryName, menuId, expectedCategoryId);
	}

	/**
	 * 메뉴 소멸·정책 제외 — 제안 당시 이름이 그대로일 때만 지운다. 이름이 바뀐 행은 다른 상품으로 재사용된 것일 수 있다.
	 * 사용자 등록 메뉴는 네이버에서 사라졌다는 이유로 지우지 않는다 (0행) — 소멸 근거가 그 메뉴를 관측한 적이 없다.
	 */
	public int softDeleteMenu(long menuId, String expectedName) {
		return prodJdbcTemplate.update(
			"UPDATE menu SET deleted_at = now(), updated_at = now() "
				+ "WHERE id = ? AND name IS NOT DISTINCT FROM CAST(? AS VARCHAR)" + MENU_WRITABLE,
			menuId, expectedName);
	}
}
