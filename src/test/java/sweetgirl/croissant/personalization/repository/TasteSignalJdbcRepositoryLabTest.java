package sweetgirl.croissant.personalization.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseStore;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.SavedStore;
import sweetgirl.croissant.store.entity.Store;

/**
 * 강한 신호 가게 수 — 저장 · 담기 · 기록이 같은 가게를 가리키면 1 로 센다(UNION). H2 에는 store 를 참조하는 표
 * (saved_stores · course_stores · bread_record)가 만들어지지 않아 랩 PG 로 돈다. 트랜잭션은 테스트마다 롤백된다.
 */
@Tag("lab")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, TasteSignalJdbcRepository.class})
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
class TasteSignalJdbcRepositoryLabTest {

	@Autowired
	private TasteSignalJdbcRepository tasteSignalJdbcRepository;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Store activeStore(int offset) {
		Long id = jdbcTemplate.queryForObject(
			"SELECT id FROM store WHERE status = 'ACTIVE' ORDER BY id OFFSET ? LIMIT 1", Long.class, offset);
		return entityManager.find(Store.class, id);
	}

	@Test
	@DisplayName("같은 가게를 저장하고 담고 기록해도 강한 신호 가게는 1곳이다. 다른 가게를 저장하면 2곳")
	void countStrongSignalStores_distinctAcrossSources() {
		Member member = new Member("신호테스트");
		entityManager.persist(member);
		Store store = activeStore(0);
		Store another = activeStore(1);
		entityManager.persist(SavedStore.create(member, store));
		Course course = Course.createCustom("신호 테스트 코스", member, false);
		entityManager.persist(course);
		entityManager.persist(CourseStore.createWithDetails(course, store, 1, 1, null, null));
		entityManager.persist(BreadRecord.builder()
			.member(member).store(store).storeName(store.getName()).regionKey("서울").memo(null)
			.lovedBreads(List.of()).eatenAt(LocalDateTime.of(2026, 9, 13, 12, 0))
			.build());
		entityManager.flush();

		assertThat(tasteSignalJdbcRepository.countStrongSignalStores(member.getId())).isEqualTo(1);

		entityManager.persist(SavedStore.create(member, another));
		entityManager.flush();

		assertThat(tasteSignalJdbcRepository.countStrongSignalStores(member.getId())).isEqualTo(2);
	}
}
