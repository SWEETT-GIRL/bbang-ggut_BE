package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.request.NoticeSendRequest;
import sweetgirl.croissant.alarm.dto.response.NoticeSendResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationListResponse;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

	private static final Long OWNER_ID = 1L;
	private static final Long ACTOR_ID = 2L;
	private static final Long COURSE_ID = 100L;

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private CourseRepository courseRepository;

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private NotificationService notificationService;

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	/** Course는 생성자가 막혀 있다 — 이 레포의 다른 테스트와 같이 mock으로 세운다. */
	private Course course(Long id, String title) {
		Course course = org.mockito.Mockito.mock(Course.class);
		org.mockito.Mockito.lenient().when(course.getId()).thenReturn(id);
		org.mockito.Mockito.lenient().when(course.getTitle()).thenReturn(title);
		return course;
	}

	private CourseNotificationReadRequest readRequest(List<Long> ids) {
		CourseNotificationReadRequest request = new CourseNotificationReadRequest();
		ReflectionTestUtils.setField(request, "notificationIds", ids);
		return request;
	}

	@Test
	@DisplayName("처음 저장되면 알림을 새로 쌓고 코스 제목을 그때 값으로 박는다")
	void recordCourseSaved_createsNewWithTitleSnapshot() {
		// mock 스터빙을 given(...) 인자 안에서 하면 바깥 스터빙이 끝나기 전에 끼어들어 터진다
		Course course = course(COURSE_ID, "연남동 한바퀴");
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			ACTOR_ID, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, COURSE_ID))
			.willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(ACTOR_ID))
			.willReturn(Optional.of(member(ACTOR_ID, "포실한 까눌레")));
		given(memberRepository.findByIdAndDeletedAtIsNull(OWNER_ID))
			.willReturn(Optional.of(member(OWNER_ID, "바삭한 크루아상")));

		notificationService.recordCourseSaved(COURSE_ID, ACTOR_ID, OWNER_ID);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).save(captor.capture());
		Notification saved = captor.getValue();
		assertThat(saved.getType()).isEqualTo(NotificationType.COURSE_SAVED);
		assertThat(saved.getTargetType()).isEqualTo(NotificationTargetType.COURSE);
		assertThat(saved.getTargetTitle()).isEqualTo("연남동 한바퀴");
	}

	@Test
	@DisplayName("같은 사람이 다시 저장하면 새 줄 없이 안 읽음으로 되살린다")
	void recordCourseSaved_renewsExisting() {
		Notification existing = Notification.activity(
			member(OWNER_ID, "바삭한 크루아상"), NotificationType.COURSE_SAVED,
			member(ACTOR_ID, "포실한 까눌레"), NotificationTargetType.COURSE, COURSE_ID,
			"옛 제목", LocalDateTime.now().minusDays(1));
		existing.markAsRead(LocalDateTime.now().minusHours(1));

		Course renamed = course(COURSE_ID, "새 제목");
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(renamed));
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			ACTOR_ID, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, COURSE_ID))
			.willReturn(Optional.of(existing));

		notificationService.recordCourseSaved(COURSE_ID, ACTOR_ID, OWNER_ID);

		verify(notificationRepository, never()).save(any());
		assertThat(existing.isRead()).isFalse();
		// 되살릴 때 제목도 지금 값으로 맞춘다 — 그 사이 코스 이름이 바뀌었을 수 있다
		assertThat(existing.getTargetTitle()).isEqualTo("새 제목");
	}

	@Test
	@DisplayName("커밋 사이에 코스가 사라졌으면 조용히 넘긴다")
	void recordCourseSaved_skipsWhenCourseGone() {
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(
			ACTOR_ID, NotificationType.COURSE_SAVED, NotificationTargetType.COURSE, COURSE_ID))
			.willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(anyLong()))
			.willReturn(Optional.of(member(ACTOR_ID, "포실한 까눌레")));

		notificationService.recordCourseSaved(COURSE_ID, ACTOR_ID, OWNER_ID);

		verify(notificationRepository, never()).save(any());
	}

	@Test
	@DisplayName("size가 50을 넘으면 50으로 자른다")
	void getMine_capsPageSize() {
		given(notificationRepository.findMine(eq(OWNER_ID), any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 50), false));

		notificationService.getMine(OWNER_ID, 0, 999);

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(notificationRepository).findMine(eq(OWNER_ID), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(50);
	}

	@Test
	@DisplayName("구버전 경로는 저장 알림만 골라 준다")
	void getMineByType_filtersToCourseSaved() {
		given(notificationRepository.findMineByType(
			eq(OWNER_ID), eq(NotificationType.COURSE_SAVED), any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

		NotificationListResponse response = notificationService
			.getMineByType(OWNER_ID, NotificationType.COURSE_SAVED, 0, 20);

		assertThat(response.notifications()).isEmpty();
		verify(notificationRepository, never()).findMine(anyLong(), any());
	}

	@Test
	@DisplayName("미읽음은 종류를 가리지 않고 센다")
	void getUnreadCount_countsAllTypes() {
		given(notificationRepository.countUnread(OWNER_ID)).willReturn(3L);

		assertThat(notificationService.getUnreadCount(OWNER_ID).unreadCount()).isEqualTo(3L);
	}

	@Test
	@DisplayName("body가 없으면 전체 읽음 처리한다")
	void markAsRead_allWhenNoBody() {
		notificationService.markAsRead(OWNER_ID, null);

		verify(notificationRepository).markAllAsRead(eq(OWNER_ID), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("id를 주면 그것만 읽음 처리한다")
	void markAsRead_onlyGivenIds() {
		given(notificationRepository.findByRecipientIdAndIdIn(OWNER_ID, List.of(7L)))
			.willReturn(List.of());

		notificationService.markAsRead(OWNER_ID, readRequest(List.of(7L)));

		verify(notificationRepository, never()).markAllAsRead(anyLong(), any());
	}

	@Test
	@DisplayName("구버전 전체 읽음은 저장 알림만 건드린다 — 못 본 공지를 읽음으로 만들면 안 된다")
	void markAsReadByType_onlyThatType() {
		notificationService.markAsReadByType(OWNER_ID, NotificationType.COURSE_SAVED, null);

		verify(notificationRepository).markAllAsReadByType(
			eq(OWNER_ID), eq(NotificationType.COURSE_SAVED), any(LocalDateTime.class));
		verify(notificationRepository, never()).markAllAsRead(anyLong(), any());
	}

	@Test
	@DisplayName("공지는 한 문장으로 전 회원에게 쌓고 받은 수를 돌려준다")
	void sendNotice_insertsForAllMembers() {
		NoticeSendRequest request = new NoticeSendRequest();
		ReflectionTestUtils.setField(request, "title", "일차 나누기가 생겼어요");
		ReflectionTestUtils.setField(request, "body", "1박 2일 빵투어를 나눠 담아보세요");
		ReflectionTestUtils.setField(request, "link", "/course/12");
		given(notificationRepository.insertNoticeForAllMembers(
			eq("일차 나누기가 생겼어요"), eq("1박 2일 빵투어를 나눠 담아보세요"), eq("/course/12"),
			any(LocalDateTime.class))).willReturn(1234);

		NoticeSendResponse response = notificationService.sendNotice(request);

		assertThat(response.recipientCount()).isEqualTo(1234);
	}
}
