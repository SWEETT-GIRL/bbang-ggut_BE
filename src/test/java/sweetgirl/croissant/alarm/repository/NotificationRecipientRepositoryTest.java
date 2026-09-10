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
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;

/**
 * 알림함이 무엇을 보여주는지를 본다.
 *
 * <p>지운 코스의 알림을 <b>숨기지 않는</b> 것이 여기서 가장 중요한 규칙이다. 예전에는 Course를
 * 조건부로 조인해 걸렀는데, 그러면 "분명히 봤는데 알림이 사라졌다"가 된다. 지금은 목록에 남기고
 * 탭했을 때 앱이 "삭제된 빵투어예요"를 띄운다. 미읽음 개수도 같은 규칙을 따라야 한다 —
 * 한쪽만 어긋나면 "점은 켜져 있는데 들어가면 아무것도 없는" 상태가 된다.
 */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:notification-test;MODE=PostgreSQL;"
		+ "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class NotificationRecipientRepositoryTest {

	@MockitoBean
	private S3Service s3Service;

	@Autowired
	private NotificationRepository notificationRepository;

	@Autowired
	private NotificationRecipientRepository recipientRepository;

	@Autowired
	private EntityManager entityManager;

	private Member owner;
	private Member actor;

	@BeforeEach
	void setUp() {
		owner = persistMember("코스 주인");
		actor = persistMember("저장한 사람");
	}

	@Test
	@DisplayName("지운 코스의 알림도 목록에 그대로 남는다")
	void findMine_keepsDeletedCourseNotification() {
		persistCourseSaved(persistCourse("살아있는 빵투어"));
		Course deleted = persistCourse("지운 빵투어");
		persistCourseSaved(deleted);
		deleted.softDelete();
		flushAndClear();

		var found = recipientRepository.findMine(owner.getId(), PageRequest.of(0, 20));

		assertThat(found.getContent())
			.extracting(r -> r.getNotification().getTargetTitle())
			.containsExactlyInAnyOrder("살아있는 빵투어", "지운 빵투어");
	}

	@Test
	@DisplayName("미읽음 개수도 지운 코스를 빼지 않는다 — 목록과 배지가 어긋나면 안 된다")
	void countUnread_matchesList() {
		Course deleted = persistCourse("지운 빵투어");
		persistCourseSaved(deleted);
		deleted.softDelete();
		flushAndClear();

		assertThat(recipientRepository.countUnread(owner.getId())).isEqualTo(1);
	}

	@Test
	@DisplayName("대상이 없는 공지도 목록에 나온다")
	void findMine_keepsNoticeWithoutTarget() {
		persistNotice("일차 나누기가 생겼어요");
		flushAndClear();

		var found = recipientRepository.findMine(owner.getId(), PageRequest.of(0, 20));

		assertThat(found.getContent())
			.extracting(r -> r.getNotification().getTitle())
			.containsExactly("일차 나누기가 생겼어요");
	}

	@Test
	@DisplayName("목록은 최신순이다 — 되살아난 알림이 맨 위로 온다")
	void findMine_sortsByNotifiedAtDesc() {
		LocalDateTime base = LocalDateTime.now().minusDays(3);
		persistCourseSaved(persistCourse("먼저 찜한 빵투어"), base);
		NotificationRecipient older = persistCourseSaved(persistCourse("나중에 되살아난 빵투어"),
			base.minusDays(1));
		older.renew(LocalDateTime.now());
		flushAndClear();

		var found = recipientRepository.findMine(owner.getId(), PageRequest.of(0, 20));

		assertThat(found.getContent())
			.extracting(r -> r.getNotification().getTargetTitle())
			.containsExactly("나중에 되살아난 빵투어", "먼저 찜한 빵투어");
	}

	@Test
	@DisplayName("전체 읽음 뒤에는 미읽음이 남지 않는다")
	void markAllAsRead_clearsBadge() {
		persistCourseSaved(persistCourse("빵투어"));
		persistNotice("공지");
		flushAndClear();

		recipientRepository.markAllAsRead(owner.getId(), LocalDateTime.now());
		flushAndClear();

		assertThat(recipientRepository.countUnread(owner.getId())).isZero();
	}

	@Test
	@DisplayName("공지 하나는 내용 한 줄이고 우편함만 사람마다 생긴다")
	void insertForAllMembers_fansOutRecipientsOnly() {
		Notification notice = notificationRepository.saveAndFlush(Notification.announcement(
			NotificationType.NOTICE, "공지", "본문", null));

		int recipients = recipientRepository.insertForAllMembers(notice.getId(), LocalDateTime.now());
		flushAndClear();

		// setUp 에서 만든 두 명 모두에게 갔다.
		assertThat(recipients).isEqualTo(2);
		assertThat(notificationRepository.findById(notice.getId())).isPresent();
		assertThat(recipientRepository.findMine(owner.getId(), PageRequest.of(0, 20)).getContent())
			.hasSize(1);
	}

	// ── 도우미 ──────────────────────────────────────────────────────────────

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
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

	private NotificationRecipient persistCourseSaved(Course course) {
		return persistCourseSaved(course, LocalDateTime.now());
	}

	private NotificationRecipient persistCourseSaved(Course course, LocalDateTime notifiedAt) {
		Notification notification = notificationRepository.saveAndFlush(Notification.activity(
			NotificationType.COURSE_SAVED, actor,
			NotificationTargetType.COURSE, course.getId(), course.getTitle()));
		return recipientRepository.saveAndFlush(
			NotificationRecipient.of(notification, owner, notifiedAt));
	}

	private NotificationRecipient persistNotice(String title) {
		Notification notification = notificationRepository.saveAndFlush(
			Notification.announcement(NotificationType.NOTICE, title, "본문", "/course/1"));
		return recipientRepository.saveAndFlush(
			NotificationRecipient.of(notification, owner, LocalDateTime.now()));
	}
}
