package sweetgirl.croissant.store.repository.search;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/** 실행 가능한 검색 SQL 한 벌 — 문장은 서비스 계층 빌더가 만들고, 리포지토리는 이것만 받아 실행한다. */
public record SearchSqlQuery(String sql, MapSqlParameterSource params) {
}
