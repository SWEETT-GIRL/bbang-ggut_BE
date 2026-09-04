package sweetgirl.croissant.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.MappingMetamodel;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

/**
 * 프록시 배치 로딩은 전역 default_batch_fetch_size 가 아니라 Store·Course 의 @BatchSize 로만 켠다.
 * 두 엔티티만 배치 대상인지, 그리고 실제로 프록시 여러 개가 SELECT 한 번으로 읽히는지 본다.
 *
 * Store 는 H2 에 테이블이 없어(geography 컬럼) 매핑만 확인하고, 실제 로딩은 Course 로 본다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:proxy-batch-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class ProxyBatchLoadingTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("Store·Course 는 프록시 배치 로딩 대상이다 — 엔티티의 @BatchSize 로 100")
	void storeAndCourse_areBatchLoadable() {
		MappingMetamodel metamodel = sessionFactory().getMappingMetamodel();

		for (Class<?> entityType : List.of(Store.class, Course.class)) {
			EntityPersister persister = metamodel.getEntityDescriptor(entityType);
			assertThat(persister.isBatchLoadable()).as(entityType.getSimpleName()).isTrue();
			assertThat(persister.getBatchSize()).as(entityType.getSimpleName()).isEqualTo(100);
		}
	}

	@Test
	@DisplayName("Course 프록시 여러 개 중 하나를 건드리면 SELECT 한 번으로 전부 읽힌다 — 행마다 SELECT 가 나가지 않는다")
	void touchingOneCourseProxy_loadsTheOthersInOneSelect() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		List<Long> courseIds = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			Course course = Course.createCustom("코스" + i, owner, false);
			entityManager.persist(course);
			courseIds.add(course.getId());
		}
		entityManager.flush();
		entityManager.clear();

		List<Course> proxies = courseIds.stream()
			.map(id -> entityManager.getReference(Course.class, id))
			.toList();
		Statistics statistics = sessionFactory().getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		proxies.get(0).getTitle();

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(proxies).allSatisfy(course -> assertThat(Hibernate.isInitialized(course)).isTrue());
	}

	private SessionFactoryImplementor sessionFactory() {
		return entityManager.getEntityManagerFactory().unwrap(SessionFactoryImplementor.class);
	}
}
