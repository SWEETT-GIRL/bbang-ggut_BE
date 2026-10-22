package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityNotificationService")
class CommunityNotificationServiceTest {

	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private NotificationRecorder notificationRecorder;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private MemberFollowRepository memberFollowRepository;

	@InjectMocks
	private CommunityNotificationService service;

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	@BeforeEach
	void recorderReturnsSaved() {
		lenient().when(notificationRecorder.recordForMembers(any(), anyList(), any())).thenAnswer(invocation -> {
			Notification notification = invocation.getArgument(0);
			ReflectionTestUtils.setField(notification, "id", 500L);
			return notification;
		});
	}

	@Test
	@DisplayName("팔로우: 문장은 '회원님', 대상은 사람(MEMBER), 푸시 이벤트는 받는 사람에게")
	void follow() {
		Member actor = member(1L, "빵순이");
		Member target = member(2L, "빵돌이");
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L, NotificationType.FOLLOW,
			NotificationTargetType.MEMBER, 1L)).willReturn(Optional.empty());

		service.notifyFollow(actor, target);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), eq(List.of(target)), any());
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 회원님을 팔로우해요");
		assertThat(captor.getValue().getTargetType()).isEqualTo(NotificationTargetType.MEMBER);
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/profile/1");
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));
	}

	@Test
	@DisplayName("같은 사람이 같은 대상에 이미 알림을 만들었으면 다시 만들지 않는다 — 팔로우/언팔 반복 도배 방지")
	void once() {
		Member actor = member(1L, "빵순이");
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(eq(1L), any(), any(), any()))
			.willReturn(Optional.of(Notification.activity(NotificationType.FOLLOW, actor,
				NotificationTargetType.MEMBER, 1L, "빵순이")));

		service.notifyFollow(actor, member(2L, "빵돌이"));

		then(notificationRecorder).shouldHaveNoInteractions();
		then(eventPublisher).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("좋아요 · 댓글: 내 것에 내가 한 건 알리지 않고, 댓글은 미리보기 40자")
	void likeAndComment() {
		Member author = member(1L, "작성자");
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", 10L);
		service.notifyRecordLike(author, record);
		then(notificationRecorder).shouldHaveNoInteractions();

		Member commenter = member(2L, "빵순이");
		BreadRecordComment comment = BreadRecordComment.of(record, commenter, "가".repeat(50));
		ReflectionTestUtils.setField(comment, "id", 100L);
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(2L, NotificationType.RECORD_COMMENT,
			NotificationTargetType.COMMENT, 100L)).willReturn(Optional.empty());

		service.notifyRecordComment(comment);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), eq(List.of(author)), any());
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 댓글을 남겼어요");
		assertThat(captor.getValue().getBody()).isEqualTo("가".repeat(40) + "…");
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/bbanglog/post/10?comments=1&commentId=100");
	}

	@Test
	@DisplayName("팔로잉 소식: 팔로워 전원에게 한 알림, 탈퇴자는 빼고, 팔로워가 없으면 아무것도 안 한다")
	void followingNewRecord() {
		Member author = member(1L, "작성자");
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", 10L);
		given(memberFollowRepository.findFollowerIds(1L)).willReturn(List.of(2L, 3L));
		Member alive = member(2L, "팔로워");
		Member withdrawn = member(3L, "탈퇴");
		withdrawn.softDelete();
		given(memberRepository.findAllById(List.of(2L, 3L))).willReturn(List.of(alive, withdrawn));
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L,
			NotificationType.FOLLOWING_NEW_RECORD, NotificationTargetType.RECORD, 10L)).willReturn(Optional.empty());

		service.notifyFollowingNewRecord(record);

		then(notificationRecorder).should().recordForMembers(any(), eq(List.of(alive)), any());
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));

		given(memberFollowRepository.findFollowerIds(1L)).willReturn(List.of());
		service.notifyFollowingNewRecord(record);
		then(notificationRecorder).shouldHaveNoMoreInteractions();
	}
}
