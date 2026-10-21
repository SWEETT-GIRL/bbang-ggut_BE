package sweetgirl.croissant.store.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.store.dto.projection.TopSubscribedStoreProjection;

/**
 * 랩 PG(운영 덤프)에서 인기 빵집 쿼리를 돌린다. 현재 최고점보다 높은 인기도를 ACTIVE 가게 셋과 DELETED 가게 하나에 주고,
 * ACTIVE 셋이 인기도 순으로 맨 앞에 오고 DELETED 는 빠지는지 본다. 테스트 트랜잭션은 롤백된다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@TestPropertySource(properties = {
	"spring.datasource.url=${lab.db.url:jdbc:postgresql://localhost:5432/soboro}",
	"spring.datasource.username=${lab.db.user:sweetgirl}",
	"spring.datasource.password=${lab.db.password:soboro-lab}",
	"spring.flyway.enabled=true",
	"spring.flyway.out-of-order=true",
	"spring.flyway.baseline-on-migrate=true",
	"spring.flyway.baseline-version=20260604.0",
	"spring.jpa.hibernate.ddl-auto=validate",
	"spring.jpa.show-sql=false"
})
class StoreRepositoryTopStoresLabTest {

	@Autowired
	private StoreRepository storeRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("ACTIVE 가게만 인기도 내림차순으로 limit 만큼 준다")
	void findTopSubscribedStores_ordersActiveStoresByPopularity() {
		List<Long> active = jdbcTemplate.queryForList(
			"SELECT id FROM store WHERE status = 'ACTIVE' ORDER BY id LIMIT 3", Long.class);
		assertThat(active).hasSize(3);
		double top = jdbcTemplate.queryForObject("SELECT COALESCE(max(popularity_score), 0) FROM store", Double.class);
		setPopularity(active.get(0), top + 10);
		setPopularity(active.get(1), top + 30);
		setPopularity(active.get(2), top + 20);
		Long deleted = jdbcTemplate.queryForObject(
			"SELECT id FROM store WHERE status = 'ACTIVE' ORDER BY id OFFSET 3 LIMIT 1", Long.class);
		jdbcTemplate.update("UPDATE store SET status = 'DELETED', popularity_score = ? WHERE id = ?", top + 100,
			deleted);

		List<Long> ids = storeRepository.findTopSubscribedStores(3).stream()
			.map(TopSubscribedStoreProjection::getId)
			.toList();

		assertThat(ids).containsExactly(active.get(1), active.get(2), active.get(0));
	}

	private void setPopularity(Long storeId, double score) {
		jdbcTemplate.update("UPDATE store SET popularity_score = ? WHERE id = ?", score, storeId);
	}
}
