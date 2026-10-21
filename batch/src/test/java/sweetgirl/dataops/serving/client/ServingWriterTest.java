package sweetgirl.dataops.serving.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import sweetgirl.dataops.serving.dto.MenuUpsertResult;

@ExtendWith(MockitoExtension.class)
class ServingWriterTest {

	@Mock
	private JdbcTemplate prodJdbcTemplate;

	@InjectMocks
	private ServingWriter servingWriter;

	@Test
	void 모든_UPDATE_는_updated_at_을_함께_갱신한다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStorePhone(1L, "02-000-0000", "02-123-4567");
		servingWriter.updateStoreInstagram(1L, null, "bbang_gram");
		servingWriter.updateMenuPrice(10L, 3000, 3500);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should(times(3)).update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getAllValues()).allSatisfy(sql -> assertThat(sql).contains("updated_at = now()"));
	}

	@Test
	void 가게_갱신은_모두_예상_이전_값과_활성_상태를_조건으로_삼는다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStorePhone(1L, "02-000-0000", "02-123-4567");
		servingWriter.updateStoreInstagram(1L, null, "bbang_gram");
		servingWriter.updateStoreNaverPlaceId(1L, null, "123456");
		servingWriter.updateStoreCategory(1L, null, "BAKERY");
		servingWriter.updateStoreRegion(1L, "서울", "성동구", "성수동", "서울", "성동구", "성수동2가");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should(times(5)).update(sqlCaptor.capture(), any(Object[].class));
		// CAS 없이 쓰면 제안 이후 어드민·사용자가 바꾼 값을 되돌린다. NULL 도 비교돼야 하므로 '=' 이 아니라 IS NOT DISTINCT FROM
		assertThat(sqlCaptor.getAllValues()).allSatisfy(sql -> assertThat(sql)
			.contains("IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			.contains("status <> 'DELETED'"));
	}

	@Test
	void updateStoreName_은_개명과_alias_보존을_한_문장으로_묶는다() {
		// 두 문장이면 UPDATE 가 0행(값이 그새 바뀜)이어도 alias 만 남는다
		given(prodJdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).willReturn(1L);

		int affected = servingWriter.updateStoreName(1L, "옛이름", "새이름", "옛이름");

		assertThat(affected).isEqualTo(1);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().queryForObject(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			.startsWith("WITH updated AS (UPDATE store SET name = ?")
			.contains("name IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			.contains("INSERT INTO store_name_alias")
			// alias 는 UPDATE 가 맞은 행에서만 나온다 — 0행이면 alias 도 들어가지 않는다
			.contains("SELECT updated.id, CAST(? AS VARCHAR), now() FROM updated")
			.contains("ON CONFLICT DO NOTHING");
		then(prodJdbcTemplate).should(never()).update(anyString(), any(Object[].class));
	}

	@Test
	void updateStoreName_은_구명이_없으면_alias_를_넣지_않는다() {
		given(prodJdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).willReturn(1L);

		servingWriter.updateStoreName(1L, null, "새이름", null);

		// alias 파라미터가 NULL 이면 CTE 의 WHERE 가 걸러낸다 — 문장은 하나로 유지한다
		then(prodJdbcTemplate).should().queryForObject(anyString(), eq(Long.class), any(Object[].class));
	}

	@Test
	void replaceBusinessHours_는_다른_트랜잭션에_끼어들어_커밋하지_않는다() throws Exception {
		var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
		given(connection.getAutoCommit()).willReturn(false);
		given(prodJdbcTemplate.execute(any(org.springframework.jdbc.core.ConnectionCallback.class)))
			.willAnswer(invocation -> ((org.springframework.jdbc.core.ConnectionCallback<?>)invocation.getArgument(0)).doInConnection(connection));
		assertThatThrownBy(() -> servingWriter.replaceBusinessHours(1L,List.of(),List.of(
			new sweetgirl.dataops.snapshot.parser.BusinessHourRow("MONDAY","09:00","18:00",false))))
			.hasMessage("EXACT_HOURS_PRIVATE_TRANSACTION_REQUIRED");
		then(connection).should(never()).commit();
		then(connection).should(never()).prepareStatement(anyString());
	}

	@Test
	void 영업시간_비교_모양은_요일_문자열_순이고_빈_세트는_빈_문자열이다() {
		assertThat(ServingWriter.hoursSignature(java.util.List.of(
			new sweetgirl.dataops.snapshot.parser.BusinessHourRow("SUNDAY", null, null, true),
			new sweetgirl.dataops.snapshot.parser.BusinessHourRow("FRIDAY", "09:00", "23:59", false))))
			.isEqualTo("FRIDAY|09:00|23:59|false;SUNDAY|||true");
		assertThat(ServingWriter.hoursSignature(java.util.List.of())).isEmpty();
	}

	@Test
	void updateStoreAddress_는_좌표가_있으면_location_을_함께_갱신한다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStoreAddress(1L, "옛 주소", "새 주소", 127.001, 37.501, null, null, null);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			.contains("ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography")
			.contains("address IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			.contains("updated_at = now()");
	}

	@Test
	void updateStoreAddress_는_지역까지_한_문장으로_옮긴다() {
		// 나눠 쓰면 중간 실패가 주소만 옮겨진 가게(좌표·지역은 옛 자리)를 남긴다
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStoreAddress(1L, "옛 주소", "새 주소", 127.001, 37.501, "서울", "성동구", "성수동");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should(times(1)).update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			.contains("address = ?")
			.contains("ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography")
			.contains("region_1st = ?").contains("region_2nd = ?").contains("region_3rd = ?");
	}

	@Test
	void updateStoreAddress_는_좌표가_없으면_주소만_갱신한다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStoreAddress(1L, "옛 주소", "새 주소", null, null, null, null, null);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).doesNotContain("location").contains("SET address = ?");
	}

	@Test
	void replaceBusinessHours_는_빈_세트면_아무것도_하지_않는다() {
		int affected = servingWriter.replaceBusinessHours(1L, java.util.List.of(), java.util.List.of());

		assertThat(affected).isZero();
		then(prodJdbcTemplate).shouldHaveNoInteractions();
	}

	@Test
	void softDeleteStore_는_상태_전환이지_물리_삭제가_아니다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.softDeleteStore(1L, "ACTIVE");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).contains("SET status = 'DELETED'").doesNotContain("DELETE FROM")
			.contains("status IS NOT DISTINCT FROM CAST(? AS VARCHAR)");
	}

	@Test
	void softDeleteMenu_는_이미_삭제됐거나_이름이_바뀌었거나_사용자_메뉴면_건드리지_않는다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(0);

		int affected = servingWriter.softDeleteMenu(10L, "암행어사");

		assertThat(affected).isZero();
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			.contains("deleted_at IS NULL")
			// 이름이 바뀐 행은 다른 상품으로 재사용된 것일 수 있다
			.contains("name IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			// 네이버에서 사라졌다는 이유로 사용자가 등록한 메뉴를 지우지 않는다
			.contains("created_by_member_id IS NULL");
	}

	@Test
	@SuppressWarnings("unchecked")
	void upsertMenu_는_겹치는_메뉴가_없으면_삽입하고_생성된_id_를_반환한다() {
		given(prodJdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
			.willReturn(List.of());
		given(prodJdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
			.willReturn(77L);

		MenuUpsertResult result = servingWriter.upsertMenu(1L, "소금빵", 2900, "설명", "소금빵");

		assertThat(result.menuId()).isEqualTo(77L);
		assertThat(result.inserted()).isTrue();
		// normalized_name 은 NOT NULL — 운영 유니크 인덱스 식과 동일 계산이어야 한다
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().queryForObject(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			.contains("normalized_name")
			.contains("lower(regexp_replace(?, '[^[:alnum:]]+', '', 'g'))")
			.contains("(SELECT id FROM menu_category WHERE name = ?)")
			.doesNotContain("COALESCE")
			.doesNotContain("'기타'"); // 룰 실패는 NULL — 본 앱 스케줄러가 AI 로 분류
		then(prodJdbcTemplate).should(never()).update(anyString(), any(Object[].class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void upsertMenu_는_정규화_이름이_같은_메뉴가_있으면_삽입_대신_가격과_설명을_갱신한다() {
		given(prodJdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
			.willReturn(List.<Object[]>of(new Object[] {88L, "생식빵", 5000, "옛 설명", false}));
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		// 공백·기호·대소문자만 다른 이름은 같은 상품 ("(1개)" 처럼 글자·숫자가 붙으면 다른 이름)
		MenuUpsertResult result = servingWriter.upsertMenu(1L, "생 식빵 !!", 5500, "촉촉", "식빵");

		assertThat(result.updated()).isTrue();
		assertThat(result.menuId()).isEqualTo(88L);
		assertThat(result.previousPrice()).isEqualTo(5000);
		assertThat(result.previousDescription()).isEqualTo("옛 설명");
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).startsWith("UPDATE menu SET price = COALESCE(?, price)");
		then(prodJdbcTemplate).should(never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void upsertMenu_는_사용자_등록_메뉴와_겹치면_아무것도_쓰지_않는다() {
		given(prodJdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
			.willReturn(List.<Object[]>of(new Object[] {99L, "소금빵", 3000, null, true}));

		MenuUpsertResult result = servingWriter.upsertMenu(1L, "소금빵", 2900, null, "소금빵");

		assertThat(result.outcome()).isEqualTo(MenuUpsertResult.Outcome.BLOCKED_USER_MENU);
		assertThat(result.menuId()).isEqualTo(99L);
		then(prodJdbcTemplate).should(never()).update(anyString(), any(Object[].class));
		then(prodJdbcTemplate).should(never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
	}

	@Test
	@SuppressWarnings("unchecked")
	void upsertMenu_는_운영_키만_같고_이름이_다른_별개_상품이면_덮어쓰지_않는다() {
		// 답례세트① 이 있는데 답례세트③ 이 오면 운영 정규화([[:alnum:]])는 둘을 못 가른다 — 09-06 실측: 가격이 ③④⑤… 값으로 덮였다
		given(prodJdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
			.willReturn(List.<Object[]>of(new Object[] {66L, "✔️답례세트①", 4500, null, false}));

		MenuUpsertResult result = servingWriter.upsertMenu(1L, "✔️답례세트③", 5000, null, "기타");

		assertThat(result.outcome()).isEqualTo(MenuUpsertResult.Outcome.BLOCKED_DISTINCT_NAME);
		assertThat(result.menuId()).isEqualTo(66L);
		then(prodJdbcTemplate).should(never()).update(anyString(), any(Object[].class));
		then(prodJdbcTemplate).should(never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
	}

	@Test
	void updateStoreRegion_은_세_지역_컬럼을_함께_갱신한다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStoreRegion(1L, "서울", "광진구", "구의동", "서울", "성동구", "성수동");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).contains("region_1st = ?").contains("region_2nd = ?").contains("region_3rd = ?")
			// 세 컬럼 모두 제안 당시 값이어야 한다 — 한 컬럼만 바뀌어도 다른 근거가 끼어든 것이다
			.contains("region_1st IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			.contains("region_2nd IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			.contains("region_3rd IS NOT DISTINCT FROM CAST(? AS VARCHAR)");
	}

	@Test
	void updateStoreCategory_는_분류_코드를_그대로_쓴다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateStoreCategory(1L, "CAFE_DESSERT", "BAKERY");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue()).contains("SET category = ?").contains("updated_at = now()");
	}

	@Test
	void updateMenuPrice_는_제안_당시_가격이_그대로인_살아_있는_비사용자_메뉴만_바꾼다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(0);

		int affected = servingWriter.updateMenuPrice(77L, null, 3500);

		assertThat(affected).isZero();
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			// 가격이 NULL 이던 메뉴도 채울 수 있어야 한다 — '=' 이면 영영 0행이다
			.contains("price IS NOT DISTINCT FROM CAST(? AS INTEGER)")
			.contains("deleted_at IS NULL")
			.contains("created_by_member_id IS NULL");
	}

	@Test
	void updateMenuCategory_는_제안_당시_종류가_그대로인_살아_있는_비사용자_메뉴만_바꾼다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(0);

		int affected = servingWriter.updateMenuCategory(77L, null, "카스테라");

		// 0행(그새 바뀜·삭제·사용자 메뉴·종류 없음)을 그대로 돌려줘 호출자가 반영 실패로 다룬다
		assertThat(affected).isZero();
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		// 미분류(NULL)도 비교되도록 IS NOT DISTINCT FROM — '=' 이면 NULL 메뉴가 영영 안 바뀐다
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), eq("카스테라"), eq(77L), isNull());
		assertThat(sqlCaptor.getValue())
			.contains("menu_category_id IS NOT DISTINCT FROM CAST(? AS BIGINT)")
			.contains("deleted_at IS NULL")
			.contains("created_by_member_id IS NULL")
			.contains("c.name = ?")
			.contains("updated_at = now()");
	}

	@Test
	void updateMenuDescription_은_사용자_등록_메뉴를_제외하고_255자로_자른다() {
		given(prodJdbcTemplate.update(anyString(), any(Object[].class))).willReturn(1);

		servingWriter.updateMenuDescription(77L, "옛 설명", "겉바속촉");

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().update(sqlCaptor.capture(), any(Object[].class));
		assertThat(sqlCaptor.getValue())
			.contains("LEFT(?, 255)")
			.contains("description IS NOT DISTINCT FROM CAST(? AS VARCHAR)")
			.contains("deleted_at IS NULL")
			.contains("created_by_member_id IS NULL");
	}
}
