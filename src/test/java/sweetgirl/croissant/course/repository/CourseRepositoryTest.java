package sweetgirl.croissant.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

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

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:course-repo-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CourseRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CourseRepository courseRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("락 조회는 살아있는 코스만 돌려주고, 지운 코스는 비어 있다")
	void findWithLock_returnsOnlyLiveCourse() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course live = Course.createCustom("살아있는 코스", owner, false);
		Course deleted = Course.createCustom("지운 코스", owner, false);
		deleted.softDelete();
		entityManager.persist(live);
		entityManager.persist(deleted);
		entityManager.flush();

		assertThat(courseRepository.findWithLockByIdAndDeletedAtIsNull(live.getId()))
			.get()
			.extracting(Course::getTitle)
			.isEqualTo("살아있는 코스");
		assertThat(courseRepository.findWithLockByIdAndDeletedAtIsNull(deleted.getId())).isEmpty();
	}

	@Test
	@DisplayName("bumpVersion 은 부를 때마다 1씩 올리고, 엔티티는 refresh 해야 새 값을 본다")
	void bumpVersion_incrementsByOne() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course course = Course.createCustom("코스", owner, false);
		entityManager.persist(course);
		entityManager.flush();
		entityManager.refresh(course);
		assertThat(course.getVersion()).isZero();

		assertThat(courseRepository.bumpVersion(course.getId())).isEqualTo(1);
		assertThat(courseRepository.bumpVersion(course.getId())).isEqualTo(1);
		entityManager.refresh(course);

		assertThat(course.getVersion()).isEqualTo(2);
	}

	@Test
	@DisplayName("엔티티를 고쳐 flush 해도 version 은 되돌아가지 않는다 — JPA 쪽에선 읽기 전용 컬럼이다")
	void entityFlush_doesNotOverwriteVersion() {
		Member owner = new Member("방장");
		entityManager.persist(owner);
		Course course = Course.createCustom("코스", owner, false);
		entityManager.persist(course);
		entityManager.flush();

		courseRepository.bumpVersion(course.getId());
		course.updateTitle("고친 제목"); // 메모리의 version 은 아직 0
		entityManager.flush();
		entityManager.refresh(course);

		assertThat(course.getTitle()).isEqualTo("고친 제목");
		assertThat(course.getVersion()).isEqualTo(1);
	}
}
