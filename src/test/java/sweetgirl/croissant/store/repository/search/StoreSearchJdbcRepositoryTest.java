package sweetgirl.croissant.store.repository.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import sweetgirl.croissant.store.config.StoreSearchProperties;

/** 검색 SQL 에만 statement 타임아웃이 걸리는지 — 자동설정 템플릿을 건드리지 않고 전용 템플릿에 건다. */
class StoreSearchJdbcRepositoryTest {

	@Test
	@DisplayName("DataSource 로 만들면 설정한 쿼리 타임아웃이 전용 템플릿에 걸린다")
	void dataSourceConstructor_appliesQueryTimeout() {
		StoreSearchProperties properties = new StoreSearchProperties();
		properties.setQueryTimeoutSeconds(2);

		StoreSearchJdbcRepository repository = new StoreSearchJdbcRepository(mock(DataSource.class), properties);

		assertThat(repository.queryTimeoutSeconds()).isEqualTo(2);
	}

	@Test
	@DisplayName("템플릿을 직접 주면(랩 테스트) 그 템플릿의 타임아웃을 그대로 쓴다 — 기본은 -1(드라이버 기본값, 무제한)")
	void templateConstructor_keepsGivenTemplate() {
		StoreSearchJdbcRepository repository = new StoreSearchJdbcRepository(
			new NamedParameterJdbcTemplate(mock(DataSource.class)));

		assertThat(repository.queryTimeoutSeconds()).isEqualTo(-1);
	}
}
