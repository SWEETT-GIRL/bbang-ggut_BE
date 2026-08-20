package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;

import sweetgirl.croissant.alarm.dto.request.CourseNotificationReadRequest;
import sweetgirl.croissant.alarm.dto.response.CourseSaveNotificationListResponse;
import sweetgirl.croissant.alarm.dto.response.UnreadCountResponse;
import sweetgirl.croissant.alarm.entity.CourseSaveNotification;
import sweetgirl.croissant.alarm.repository.CourseSaveNotificationRepository;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class CourseSaveNotificationServiceTest {

	@Mock
	CourseSaveNotificationRepository courseSaveNotificationRepository;
	@Mock
	CourseRepository courseRepository;
	@Mock
	MemberRepository memberRepository;

	@InjectMocks
	CourseSaveNotificationService courseSaveNotificationService;

	// ───────────────────────── record ─────────────────────────

	@Test
	@DisplayName("record: 처음 찜하면 알림을 새로 쌓는다")
	void record_createsNew() {
		given(courseSaveNotificationRepository.findByActorIdAndCourseId(2L, 10L)).willReturn(Optional.empty());
		given(courseRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.of(mock(Course.class)));
		given(memberRepository.findByIdAndDeletedAtIsNull(anyLong())).willReturn(Optional.of(mock(Member.class)));

		courseSaveNotificationService.record(10L, 2L, 1L);

		then(courseSaveNotificationRepository).should().save(any(CourseSaveNotification.class));
	}

	@Test
	@DisplayName("record: 같은 사람이 다시 찜하면 새 줄 없이 안 읽음으로 되살린다")
	void record_renewsExisting() {
		CourseSaveNotification existing = CourseSaveNotification.create(
			mock(Member.class), mock(Member.class), mock(Course.class), LocalDateTime.now().minusDays(3));
		existing.markAsRead(LocalDateTime.now().minusDays(2));
		given(courseSaveNotificationRepository.findByActorIdAndCourseId(2L, 10L)).willReturn(Optional.of(existing));

		courseSaveNotificationService.record(10L, 2L, 1L);

		assertThat(existing.isRead()).isFalse();
		then(courseSaveNotificationRepository).should(never()).save(any(CourseSaveNotification.class));
	}

	@Test
	@DisplayName("record: 커밋 사이에 코스가 사라졌으면 조용히 넘긴다")
	void record_skipsWhenCourseGone() {
		given(courseSaveNotificationRepository.findByActorIdAndCourseId(2L, 10L)).willReturn(Optional.empty());
		given(courseRepository.findByIdAndDeletedAtIsNull(10L)).willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(anyLong())).willReturn(Optional.of(mock(Member.class)));

		courseSaveNotificationService.record(10L, 2L, 1L);

		then(courseSaveNotificationRepository).should(never()).save(any(CourseSaveNotification.class));
	}

	// ───────────────────────── getMyNotifications ─────────────────────────

	@Test
	@DisplayName("getMyNotifications: size가 50을 넘으면 50으로 자른다")
	void getMyNotifications_capsPageSize() {
		given(courseSaveNotificationRepository.findByOwner(anyLong(), any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 50), false));

		CourseSaveNotificationListResponse response = courseSaveNotificationService.getMyNotifications(1L, 0, 999);

		assertThat(response.notifications()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		then(courseSaveNotificationRepository).should().findByOwner(1L, PageRequest.of(0, 50));
	}

	// ───────────────────────── getUnreadCount ─────────────────────────

	@Test
	@DisplayName("getUnreadCount: 안 읽은 알림 개수를 준다")
	void getUnreadCount_success() {
		given(courseSaveNotificationRepository.countByOwnerIdAndReadAtIsNull(1L)).willReturn(3L);

		UnreadCountResponse response = courseSaveNotificationService.getUnreadCount(1L);

		assertThat(response.unreadCount()).isEqualTo(3L);
	}

	// ───────────────────────── markAsRead ─────────────────────────

	@Test
	@DisplayName("markAsRead: body가 없으면 전체 읽음 처리한다")
	void markAsRead_allWhenNoBody() {
		courseSaveNotificationService.markAsRead(1L, null);

		then(courseSaveNotificationRepository).should().markAllAsRead(anyLong(), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("markAsRead: id를 주면 그것만 읽음 처리한다")
	void markAsRead_selected() {
		CourseSaveNotification target = CourseSaveNotification.create(
			mock(Member.class), mock(Member.class), mock(Course.class), LocalDateTime.now());
		CourseNotificationReadRequest request = new CourseNotificationReadRequest();
		org.springframework.test.util.ReflectionTestUtils.setField(request, "notificationIds", List.of(7L));
		given(courseSaveNotificationRepository.findByOwnerIdAndIdIn(1L, List.of(7L))).willReturn(List.of(target));

		courseSaveNotificationService.markAsRead(1L, request);

		assertThat(target.isRead()).isTrue();
		then(courseSaveNotificationRepository).should(never()).markAllAsRead(anyLong(), any(LocalDateTime.class));
	}
}
