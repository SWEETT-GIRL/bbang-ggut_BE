package sweetgirl.croissant.alarm.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.alarm.entity.CourseSaveNotification;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

/**
 * 지운 코스의 알림이 목록·미읽음수 양쪽에서 함께 빠지는지 본다.
 * 한쪽만 빠지면 "점은 켜져 있는데 들어가면 아무것도 없는" 상태가 된다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:course-save-noti-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class CourseSaveNotificationRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private CourseSaveNotificationRepository courseSaveNotificationRepository;

	@Autowired
	private EntityManager entityManager;

	private Member owner;
	private Member actor;

	@BeforeEach
	void setUp() {
		owner = persistMember("코스 주인");
		actor = persistMember("찜한 사람");
	}

	private Member persistMember(String nickname) {
		Member member = new Member(nickname);
		entityManager.persist(member);
		return member;
	}

	private Course persistCourse(String title) {
		Course course = Course.createCustom(title, owner, true);
		entityManager.persist(course);
		return course;
	}

	private CourseSaveNotification persistNotification(Course course) {
		return courseSaveNotificationRepository.saveAndFlush(
			CourseSaveNotification.create(owner, actor, course, LocalDateTime.now()));
	}

	@Test
	@DisplayName("findByOwner: 살아있는 코스의 알림만 준다")
	void findByOwner_excludesDeletedCourse() {
		persistNotification(persistCourse("살아있는 빵투어"));
		Course deleted = persistCourse("지운 빵투어");
		persistNotification(deleted);
		deleted.softDelete();
		entityManager.flush();
		entityManager.clear();

		var found = courseSaveNotificationRepository.findByOwner(owner.getId(), PageRequest.of(0, 20));

		assertThat(found.getContent())
			.extracting(n -> n.getCourse().getTitle())
			.containsExactly("살아있는 빵투어");
	}

	@Test
	@DisplayName("countUnreadByOwner: 지운 코스의 안 읽은 알림은 안 센다")
	void countUnreadByOwner_excludesDeletedCourse() {
		persistNotification(persistCourse("살아있는 빵투어"));
		Course deleted = persistCourse("지운 빵투어");
		persistNotification(deleted);
		deleted.softDelete();
		entityManager.flush();
		entityManager.clear();

		assertThat(courseSaveNotificationRepository.countUnreadByOwner(owner.getId())).isEqualTo(1L);
	}
}
