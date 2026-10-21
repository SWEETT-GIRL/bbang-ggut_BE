package sweetgirl.croissant.course.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.hibernate.Hibernate;
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
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:saved-course-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class SavedCourseRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private SavedCourseRepository savedCourseRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	@DisplayName("찜 목록은 코스와 작성자를 같이 읽는다 — 찜마다 코스·회원 SELECT 가 따로 나가지 않는다")
	void findAllByMemberId_fetchesCourseAndAuthor() {
		Member author = new Member("작성자");
		Member saver = new Member("찜한 사람");
		entityManager.persist(author);
		entityManager.persist(saver);
		Course course = Course.createCustom("코스", author, true);
		entityManager.persist(course);
		entityManager.persist(SavedCourse.create(saver, course));
		entityManager.flush();
		// 영속성 컨텍스트에 남아 있으면 프록시 없이 그대로 돌아오므로 비우고 다시 읽는다
		entityManager.clear();

		List<SavedCourse> saved = savedCourseRepository.findAllByMemberId(saver.getId());

		assertThat(saved).hasSize(1);
		SavedCourse row = saved.get(0);
		assertThat(Hibernate.isInitialized(row.getCourse())).isTrue();
		assertThat(Hibernate.isInitialized(row.getCourse().getMember())).isTrue();
		assertThat(row.getCourse().getMember().getNickname()).isEqualTo("작성자");
	}
}
