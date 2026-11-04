package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("CommunityNotificationService")
class CommunityNotificationServiceTest {

	@Mock
	private NotificationRepository notificationRepository;
	@Mock
	private sweetgirl.croissant.alarm.repository.NotificationRecipientRepository notificationRecipientRepository;
	@Mock
	private sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository notificationDeliveryRepository;
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
			NotificationTargetType.MEMBER, 2L)).willReturn(Optional.empty());

		service.notifyFollow(actor, target);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), eq(List.of(target)), any());
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 회원님을 팔로우해요");
		assertThat(captor.getValue().getTargetType()).isEqualTo(NotificationTargetType.MEMBER);
		// 대상은 받는 사람 — 같은 사람이 다른 사람을 팔로우하면 새 알림이어야 한다(키가 actor 였던 10/22 버그)
		assertThat(captor.getValue().getTargetId()).isEqualTo(2L);
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/profile/1");
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));
	}

	@Test
	@DisplayName("톡: 날마다 새로 알린다(있는지 찾지 않음), 대상은 받는 사람, 링크는 톡 목록")
	void pat() {
		Member actor = member(1L, "빵순이");
		Member target = member(2L, "빵돌이");

		service.notifyPat(actor, target);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), eq(List.of(target)), any());
		assertThat(captor.getValue().getType()).isEqualTo(NotificationType.PAT);
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 회원님에게 톡을 쳤어요");
		assertThat(captor.getValue().getTargetId()).isEqualTo(2L);
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/pats");
		then(notificationRepository).shouldHaveNoInteractions();
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));
	}

	@Test
	@DisplayName("같이 간 사람: 기록당 알림 한 행, 나중에 더 태그하면 그 행에 받는 사람만 늘린다")
	void companionTag() {
		Member author = member(1L, "빵순이");
		BreadRecord record = BreadRecord.builder().member(author).storeName("빵집").build();
		ReflectionTestUtils.setField(record, "id", 10L);
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L,
			NotificationType.COMPANION_TAG, NotificationTargetType.RECORD, 10L)).willReturn(Optional.empty());

		service.notifyCompanionTag(record, List.of(member(2L, "빵돌이")));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), anyList(), any());
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 회원님과 같이 간 빵로그를 올렸어요");
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/bbanglog/post/10");
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));

		Notification existing = captor.getValue();
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L,
			NotificationType.COMPANION_TAG, NotificationTargetType.RECORD, 10L)).willReturn(Optional.of(existing));
		Member third = member(3L, "빵삼이");
		given(notificationRecorder.addRecipient(eq(existing), eq(third), any())).willReturn(true);

		service.notifyCompanionTag(record, List.of(third));

		then(notificationRecorder).should().addRecipient(eq(existing), eq(third), any());
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 3L));
	}

	@Test
	@DisplayName("일행 초대: 대상은 빵투어(COURSE), 문장은 초대한 사람, 링크는 초대 화면")
	void courseMemberInvite() {
		Member inviter = member(1L, "빵순이");
		Course course = mock(Course.class);
		given(course.getId()).willReturn(10L);
		given(course.getTitle()).willReturn("성수 빵투어");
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L, NotificationType.INVITE,
			NotificationTargetType.COURSE, 10L)).willReturn(Optional.empty());

		service.notifyCourseMemberInvite(inviter, course, List.of(member(2L, "빵돌이")));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), anyList(), any());
		assertThat(captor.getValue().getType()).isEqualTo(NotificationType.INVITE);
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 빵투어에 초대했어요");
		assertThat(captor.getValue().getBody()).isEqualTo("성수 빵투어");
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/courses/10/member-invite");
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));
	}

	@Test
	@DisplayName("멘션: 댓글 하나에 여러 명을 언급하면 한 행에 받는 사람 전부, 댓글 쓴 사람 자신은 뺀다")
	void mentionManyAtOnce() {
		Member commenter = member(1L, "빵순이");
		BreadRecord record = BreadRecord.builder().member(member(9L, "글쓴이")).storeName("빵집").build();
		ReflectionTestUtils.setField(record, "id", 10L);
		BreadRecordComment comment = BreadRecordComment.of(record, commenter, "@빵돌이 @빵삼이 가자");
		ReflectionTestUtils.setField(comment, "id", 77L);
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L,
			NotificationType.COMMENT_MENTION, NotificationTargetType.COMMENT, 77L)).willReturn(Optional.empty());
		Member second = member(2L, "빵돌이");
		Member third = member(3L, "빵삼이");

		service.notifyMention(comment, List.of(second, third, commenter));

		then(notificationRecorder).should().recordForMembers(any(), eq(List.of(second, third)), any());
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 3L));
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

	@Test
	@DisplayName("팔로우 요청: 받는 사람에게, 링크는 받은 요청 목록 · 수락: 요청한 사람에게, 링크는 수락한 사람 프로필")
	void followRequestAndAccepted() {
		Member requester = member(1L, "빵순이");
		Member target = member(2L, "빵돌이");
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(eq(1L), any(), any(), any()))
			.willReturn(Optional.empty());
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(eq(2L), any(), any(), any()))
			.willReturn(Optional.empty());

		service.notifyFollowRequest(requester, target);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), eq(List.of(target)), any());
		assertThat(captor.getValue().getType()).isEqualTo(NotificationType.FOLLOW_REQUEST);
		assertThat(captor.getValue().getTitle()).isEqualTo("빵순이님이 팔로우를 요청했어요");
		assertThat(captor.getValue().getTargetId()).isEqualTo(2L);
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/follow-requests");
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 2L));

		service.notifyFollowAccepted(target, requester);

		ArgumentCaptor<Notification> accepted = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(accepted.capture(), eq(List.of(requester)), any());
		assertThat(accepted.getValue().getType()).isEqualTo(NotificationType.FOLLOW_ACCEPTED);
		assertThat(accepted.getValue().getTitle()).isEqualTo("빵돌이님이 팔로우 요청을 수락했어요");
		assertThat(accepted.getValue().getDeepLink()).isEqualTo("/profile/2");
		then(eventPublisher).should().publishEvent(PushRequestedEvent.to(500L, 1L));
	}

	@Test
	@DisplayName("요청 알림 거두기: 있으면 우편함 · 발송 대장 · 알림 행을 지우고, 없으면 아무것도 안 한다")
	void retractFollowRequest() {
		Member requester = member(1L, "빵순이");
		Notification notification = Notification.activity(NotificationType.FOLLOW_REQUEST, requester,
			NotificationTargetType.MEMBER, 2L, "빵돌이");
		ReflectionTestUtils.setField(notification, "id", 700L);
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L, NotificationType.FOLLOW_REQUEST,
			NotificationTargetType.MEMBER, 2L)).willReturn(Optional.of(notification));

		service.retractFollowRequestNotification(1L, 2L);

		then(notificationDeliveryRepository).should().deleteAllByNotificationIdIn(List.of(700L));
		then(notificationRecipientRepository).should().deleteAllByNotificationIdIn(List.of(700L));
		then(notificationRepository).should().deleteAllByIdInBatch(List.of(700L));

		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(1L, NotificationType.FOLLOW_REQUEST,
			NotificationTargetType.MEMBER, 3L)).willReturn(Optional.empty());
		service.retractFollowRequestNotification(1L, 3L);
		then(notificationRepository).shouldHaveNoMoreInteractions();
	}

	@Test
	@DisplayName("답글: 부모 댓글 작성자에게 — 내 댓글에 내가 단 답글 · 부모 작성자가 빵로그 작성자면 알리지 않는다")
	void commentReply() {
		Member author = member(1L, "작성자");
		BreadRecord record = BreadRecord.builder().member(author).storeName("밀도").isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", 10L);
		Member parentAuthor = member(2L, "빵순이");
		Member replier = member(3L, "빵돌이");
		BreadRecordComment parent = BreadRecordComment.of(record, parentAuthor, "소금빵 최고");
		ReflectionTestUtils.setField(parent, "id", 100L);
		BreadRecordComment reply = BreadRecordComment.reply(record, replier, "저도요", parent);
		ReflectionTestUtils.setField(reply, "id", 101L);
		given(notificationRepository.findByActorIdAndTypeAndTargetTypeAndTargetId(3L, NotificationType.COMMENT_REPLY,
			NotificationTargetType.COMMENT, 101L)).willReturn(Optional.empty());

		service.notifyCommentReply(reply, parentAuthor);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		then(notificationRecorder).should().recordForMembers(captor.capture(), eq(List.of(parentAuthor)), any());
		assertThat(captor.getValue().getTitle()).isEqualTo("빵돌이님이 회원님의 댓글에 답글을 남겼어요");
		assertThat(captor.getValue().getBody()).isEqualTo("저도요");
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/bbanglog/post/10?comments=1&commentId=101");

		service.notifyCommentReply(reply, replier);
		service.notifyCommentReply(reply, author);
		then(notificationRecorder).shouldHaveNoMoreInteractions();
	}
}
