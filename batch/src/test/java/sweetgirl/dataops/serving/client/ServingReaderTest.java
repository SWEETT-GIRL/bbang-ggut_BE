package sweetgirl.dataops.serving.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import sweetgirl.dataops.serving.dto.MenuCurrentValues;

@ExtendWith(MockitoExtension.class)
class ServingReaderTest {

	@Mock
	private JdbcTemplate prodJdbcTemplate;

	@InjectMocks
	private ServingReader servingReader;

	@Test
	@SuppressWarnings("unchecked")
	void findMenus_는_삭제_표시된_메뉴를_읽지_않고_종류를_함께_읽는다() throws Exception {
		// 09-19 제외 필터 정리로 삭제 표시된 메뉴(62,583건)가 정책 재판정에 다시 들어와 removed 가 또 제안되지 않게
		given(prodJdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).willReturn(List.of());

		servingReader.findMenus(1L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		then(prodJdbcTemplate).should().query(sqlCaptor.capture(), any(RowMapper.class), eq(1L));
		assertThat(sqlCaptor.getValue())
			.contains("m.deleted_at IS NULL")
			.contains("LEFT JOIN menu_category mc ON mc.id = m.menu_category_id");
	}

	@Test
	@SuppressWarnings("unchecked")
	void findMenus_는_미분류_메뉴의_종류_id_를_0_이_아니라_null_로_읽는다() throws Exception {
		// getLong 은 NULL 을 0 으로 준다 — 그러면 재분류 반영의 compare-and-set 이 menu_category_id = 0 을 찾아 영영 실패한다
		given(prodJdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class))).willReturn(List.of());
		servingReader.findMenus(1L);
		ArgumentCaptor<RowMapper<MenuCurrentValues>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
		then(prodJdbcTemplate).should().query(anyString(), mapperCaptor.capture(), eq(1L));

		ResultSet resultSet = mock(ResultSet.class);
		given(resultSet.getLong("id")).willReturn(77L);
		given(resultSet.getString("name")).willReturn("옛날 카스테라");
		given(resultSet.getObject("price", Integer.class)).willReturn(8000);
		given(resultSet.getString("description")).willReturn(null);
		given(resultSet.getBoolean("user_added")).willReturn(false);
		given(resultSet.getObject("menu_category_id", Long.class)).willReturn(null);
		given(resultSet.getString("category")).willReturn(null);

		MenuCurrentValues menu = mapperCaptor.getValue().mapRow(resultSet, 0);

		assertThat(menu.menuId()).isEqualTo(77L);
		assertThat(menu.categoryId()).isNull();
		assertThat(menu.category()).isNull();
	}
}
