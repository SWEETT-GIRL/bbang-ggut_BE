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
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

/**
 * 지운 코스의 알림이 목록·미읽음수 양쪽에서 함께 빠지는지 본다.
 * 한쪽만 빠지면 "점은 켜져 있는데 들어가면 아무것도 없는" 상태가 된다.
 *
 * <p>알림을 한 테이블로 합치면서 대상을 관계로 묶지 않게 됐다. 코스를 조건부로 조인해 거르는데,
 * 그 조인이 공지처럼 대상이 없는 알림까지 걸러내지 않는지도 여기서 확인한다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:notification-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class NotificationRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private EntityManager entityManager;

	private Member owner;
	private Member actor;

	@BeforeEach
	void setUp() {
		owner = persistMember("코스 주인");
		actor = persistMember("저장한 사람");
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

	private Notification persistCourseSaved(Course course) {
		return notificationRepository.saveAndFlush(Notification.activity(
			owner, NotificationType.COURSE_SAVED, actor,
			NotificationTargetType.COURSE, course.getId(), course.getTitle(), LocalDateTime.now()));
	}

	private Notification persistNotice(String title) {
		return notificationRepository.saveAndFlush(
			Notification.notice(owner, title, "본문", "/course/1", LocalDateTime.now()));
	}

	@Test
	@DisplayName("findMine: 살아있는 코스의 알림만 준다")
	void findMine_excludesDeletedCourse() {
		persistCourseSaved(persistCourse("살아있는 빵투어"));
		Course deleted = persistCourse("지운 빵투어");
		persistCourseSaved(deleted);
		deleted.softDelete();
		entityManager.flush();
		entityManager.clear();

		var found = notificationRepository.findMine(owner.getId(), PageRequest.of(0, 20));

		assertThat(found.getContent())
			.extracting(Notification::getTargetTitle)
			.containsExactly("살아있는 빵투어");
	}

	@Test
	@DisplayName("findMine: 대상이 없는 공지는 코스 조인에 걸려 사라지면 안 된다")
	void findMine_keepsNoticeWithoutTarget() {
		persistNotice("일차 나누기가 생겼어요");
		entityManager.flush();
		entityManager.clear();

		var found = notificationRepository.findMine(owner.getId(), PageRequest.of(0, 20));

		assertThat(found.getContent())
			.extracting(Notification::getTitle)
			.containsExactly("일차 나누기가 생겼어요");
	}

	@Test
	@DisplayName("countUnread: 지운 코스의 안 읽은 알림은 안 센다")
	void countUnread_excludesDeletedCourse() {
		persistCourseSaved(persistCourse("살아있는 빵투어"));
		Course deleted = persistCourse("지운 빵투어");
		persistCourseSaved(deleted);
		deleted.softDelete();
		entityManager.flush();
		entityManager.clear();

		assertThat(notificationRepository.countUnread(owner.getId())).isEqualTo(1L);
	}

	@Test
	@DisplayName("countUnread: 공지도 함께 센다 — 벨은 종류를 가리지 않는다")
	void countUnread_countsNotice() {
		persistCourseSaved(persistCourse("살아있는 빵투어"));
		persistNotice("일차 나누기가 생겼어요");
		entityManager.flush();
		entityManager.clear();

		assertThat(notificationRepository.countUnread(owner.getId())).isEqualTo(2L);
	}

	@Test
	@DisplayName("구버전 경로: 저장 알림만 세고 공지는 빼야 한다")
	void countUnreadByType_onlyCourseSaved() {
		persistCourseSaved(persistCourse("살아있는 빵투어"));
		persistNotice("일차 나누기가 생겼어요");
		entityManager.flush();
		entityManager.clear();

		assertThat(notificationRepository.countUnreadByType(owner.getId(), NotificationType.COURSE_SAVED))
			.isEqualTo(1L);
	}

	@Test
	@DisplayName("공지 벌크 INSERT는 탈퇴하지 않은 회원 수만큼 넣는다")
	void insertNoticeForAllMembers_countsLivingMembers() {
		entityManager.flush();

		int inserted = notificationRepository.insertNoticeForAllMembers(
			"제목", "본문", "/course/1", LocalDateTime.now());

		// setUp에서 만든 두 명 이상(다른 테스트가 남긴 회원은 트랜잭션 롤백으로 없다)
		assertThat(inserted).isGreaterThanOrEqualTo(2);
	}
}
