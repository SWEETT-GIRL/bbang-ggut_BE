package sweetgirl.dataops.serving.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.mock;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

@ExtendWith(MockitoExtension.class)
class ProdSchemaGuardTest {

	@Mock
	private JdbcTemplate prodJdbcTemplate;

	private void stubProdColumns(Map<String, List<String>> columnsByTable) {
		willAnswer(invocation -> {
			RowCallbackHandler handler = invocation.getArgument(1);
			for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
				for (String column : entry.getValue()) {
					ResultSet resultSet = mock(ResultSet.class);
					given(resultSet.getString("table_name")).willReturn(entry.getKey());
					given(resultSet.getString("column_name")).willReturn(column);
					handler.processRow(resultSet);
				}
			}
			return null;
		}).given(prodJdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
	}

	@Test
	void verify_모든_참조_컬럼이_있으면_통과한다() {
		stubProdColumns(ProdSchemaGuard.REQUIRED_COLUMNS);

		ProdSchemaGuard guard = new ProdSchemaGuard(prodJdbcTemplate);

		assertThatCode(guard::verify).doesNotThrowAnyException();
	}

	@Test
	void verify_참조_컬럼이_없으면_기동을_실패시킨다() {
		Map<String, List<String>> withoutStorePhone = Map.of(
			"store", List.of("id", "name", "address", "status", "naver_place_id"),
			"menu", ProdSchemaGuard.REQUIRED_COLUMNS.get("menu"),
			"contribution_report", ProdSchemaGuard.REQUIRED_COLUMNS.get("contribution_report"),
			"menu_contribution_report", ProdSchemaGuard.REQUIRED_COLUMNS.get("menu_contribution_report"));
		stubProdColumns(withoutStorePhone);

		ProdSchemaGuard guard = new ProdSchemaGuard(prodJdbcTemplate);

		assertThatThrownBy(guard::verify)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("store.phone");
	}

	@Test
	void verify_테이블_자체가_없으면_기동을_실패시킨다() {
		stubProdColumns(Map.of(
			"store", ProdSchemaGuard.REQUIRED_COLUMNS.get("store"),
			"menu", ProdSchemaGuard.REQUIRED_COLUMNS.get("menu"),
			"contribution_report", ProdSchemaGuard.REQUIRED_COLUMNS.get("contribution_report")));

		ProdSchemaGuard guard = new ProdSchemaGuard(prodJdbcTemplate);

		assertThatThrownBy(guard::verify)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("menu_contribution_report.");
	}
}
