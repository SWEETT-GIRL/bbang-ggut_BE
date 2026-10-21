package sweetgirl.dataops.serving.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.zaxxer.hikari.HikariDataSource;

import sweetgirl.dataops.serving.client.ServingReader;
import sweetgirl.dataops.serving.client.ServingWriter;

/**
 * 운영 DB 접속 구성 — dataops.prod-db.url 미설정 시 이 구성 전체가 비활성화된다 (서버는 뜸).
 * DataSource 를 스프링 빈으로 노출하지 않아 주 DB(spring.datasource) 자동 구성과 간섭하지 않는다.
 * JPA 미사용 — 운영 스키마의 엔티티 소유권을 만들지 않는다 (설계서 7.5).
 */
@Configuration
@ConditionalOnExpression("!'${dataops.prod-db.url:}'.isBlank()")
public class ProdDataSourceConfig implements DisposableBean {

	private HikariDataSource prodDataSource;

	@Bean
	public JdbcTemplate prodJdbcTemplate(
			@Value("${dataops.prod-db.url}") String url,
			@Value("${dataops.prod-db.username}") String username,
			@Value("${dataops.prod-db.password}") String password) {
		HikariDataSource dataSource = new HikariDataSource();
		dataSource.setPoolName("dataops-prod");
		dataSource.setJdbcUrl(url);
		dataSource.setUsername(username);
		dataSource.setPassword(password);
		dataSource.setMaximumPoolSize(2);
		this.prodDataSource = dataSource;
		return new JdbcTemplate(dataSource);
	}

	@Bean
	public ProdSchemaGuard prodSchemaGuard(JdbcTemplate prodJdbcTemplate) {
		ProdSchemaGuard guard = new ProdSchemaGuard(prodJdbcTemplate);
		guard.verify();
		return guard;
	}

	@Bean
	public ServingReader servingReader(JdbcTemplate prodJdbcTemplate, ProdSchemaGuard prodSchemaGuard) {
		return new ServingReader(prodJdbcTemplate);
	}

	@Bean
	@ConditionalOnProperty(name = "dataops.serving.write-enabled", havingValue = "true")
	public ServingWriter servingWriter(JdbcTemplate prodJdbcTemplate, ProdSchemaGuard prodSchemaGuard) {
		return new ServingWriter(prodJdbcTemplate);
	}

	@Override
	public void destroy() {
		if (prodDataSource != null) {
			prodDataSource.close();
		}
	}
}
