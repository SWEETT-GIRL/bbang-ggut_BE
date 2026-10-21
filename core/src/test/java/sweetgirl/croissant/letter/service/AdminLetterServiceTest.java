package sweetgirl.croissant.letter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.DeliveryStatus;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationDeliveryRepository;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.AdminLetterReplyRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterResolveRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterSendRequest;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendStatsResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendSummaryResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadListResponse;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.exception.LetterException;
import sweetgirl.croissant.letter.repository.LetterMessageRepository;
import sweetgirl.croissant.letter.repository.LetterThreadRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class AdminLetterServiceTest {

	@Mock
	private LetterThreadRepository letterThreadRepository;

	@Mock
	private LetterMessageRepository letterMessageRepository;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private NotificationRepository notificationRepository;

	@Mock
	private NotificationRecorder notificationRecorder;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private NotificationDeliveryRepository notificationDeliveryRepository;

	@InjectMocks
	private AdminLetterService adminLetterService;

	private static final Long SEND_ID = 900L;

	/** 기록기는 저장한 알림을 돌려준다 — id를 채워 돌려줘야 푸시 이벤트가 그 id로 나간다. */
	@BeforeEach
	void recorderReturnsSavedNotification() {
		lenient().when(notificationRecorder.recordFor(any(Notification.class), any(Member.class), any()))
			.thenAnswer(inv -> withId(inv.getArgument(0), SEND_ID));
		lenient().when(notificationRecorder.recordForMembers(any(Notification.class), anyList(), any()))
			.thenAnswer(inv -> withId(inv.getArgument(0), SEND_ID));
	}

	private static Notification withId(Notification notification, Long id) {
		ReflectionTestUtils.setField(notification, "id", id);
		return notification;
	}

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private LetterThread thread(Long id, Member member) {
		LetterThread thread = LetterThread.create(member, LocalDateTime.of(2026, 9, 3, 10, 0));
		ReflectionTestUtils.setField(thread, "id", id);
		return thread;
	}

	private LetterMessage message(LetterThread thread, LetterSender sender, String body) {
		return LetterMessage.create(thread, sender, body);
	}

	private AdminLetterSendRequest sendRequest(List<Long> memberIds, String body) {
		AdminLetterSendRequest request = new AdminLetterSendRequest();
		ReflectionTestUtils.setField(request, "memberIds", memberIds);
		ReflectionTestUtils.setField(request, "body", body);
		return request;
	}

	private AdminLetterReplyRequest replyRequest(String body) {
		AdminLetterReplyRequest request = new AdminLetterReplyRequest();
		ReflectionTestUtils.setField(request, "body", body);
		return request;
	}

	@Test
	@DisplayName("목록은 마지막 편지 미리보기와 미읽음 수를 붙여 준다")
	void getThreads_attachesPreviewAndUnreadCount() {
		Member member = member(7L, "포실한 까눌레");
		LetterThread thread = thread(11L, member);
		given(letterThreadRepository.findAllForAdmin(any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(thread), PageRequest.of(0, 20), false));
		given(letterMessageRepository.findLatestPerThread(List.of(11L)))
			.willReturn(List.of(message(thread, LetterSender.MEMBER, "네 좋아요!")));
		given(letterMessageRepository.countUnreadPerThread(List.of(11L), LetterSender.MEMBER))
			.willReturn(List.<Object[]>of(new Object[] {11L, 2L}));

		AdminLetterThreadListResponse response = adminLetterService.getThreads(0, 20);

		assertThat(response.threads()).hasSize(1);
		assertThat(response.threads().get(0).nickname()).isEqualTo("포실한 까눌레");
		assertThat(response.threads().get(0).lastMessagePreview()).isEqualTo("네 좋아요!");
		assertThat(response.threads().get(0).unreadCount()).isEqualTo(2L);
	}

	@Test
	@DisplayName("긴 편지는 미리보기에서 잘린다")
	void getThreads_truncatesLongPreview() {
		Member member = member(7L, "포실한 까눌레");
		LetterThread thread = thread(11L, member);
		String longBody = "가".repeat(200);
		given(letterThreadRepository.findAllForAdmin(any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(thread), PageRequest.of(0, 20), false));
		given(letterMessageRepository.findLatestPerThread(List.of(11L)))
			.willReturn(List.of(message(thread, LetterSender.MEMBER, longBody)));
		given(letterMessageRepository.countUnreadPerThread(List.of(11L), LetterSender.MEMBER))
			.willReturn(List.of());

		AdminLetterThreadListResponse response = adminLetterService.getThreads(0, 20);

		assertThat(response.threads().get(0).lastMessagePreview()).hasSize(61).endsWith("…");
		assertThat(response.threads().get(0).unreadCount()).isZero();
	}

	@Test
	@DisplayName("스레드가 없으면 추가 조회를 하지 않는다")
	void getThreads_skipsLookupsWhenEmpty() {
		given(letterThreadRepository.findAllForAdmin(any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));

		adminLetterService.getThreads(0, 20);

		verify(letterMessageRepository, never()).findLatestPerThread(anyList());
		verify(letterMessageRepository, never()).countUnreadPerThread(anyList(), any());
	}

	@Test
	@DisplayName("답장하면 사용자가 보낸 편지가 읽음 처리된다")
	void reply_marksMemberLettersRead() {
		LetterThread thread = thread(11L, member(7L, "포실한 까눌레"));
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		adminLetterService.reply(11L, replyRequest("감사합니다!"));

		verify(letterMessageRepository).markAllAsRead(eq(11L), eq(LetterSender.MEMBER), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("우리가 보낸 편지는 그 대화를 목록에 올리지 않는다 — 답장도, 선발신도")
	void adminLetters_doNotListThread() {
		Member member = member(7L, "포실한 까눌레");
		LetterThread thread = thread(11L, member);
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread));
		given(letterThreadRepository.findByMemberId(7L)).willReturn(Optional.of(thread));
		given(memberRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(member));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		adminLetterService.reply(11L, replyRequest("감사합니다!"));
		adminLetterService.send(sendRequest(List.of(7L), "인터뷰 가능하실까요?"));

		// 목록에 오르는 건 사용자가 말을 걸 때뿐이다. 일괄 발신이 2,700개 방을 목록에
		// 올려버리면 고친 의미가 없다
		assertThat(thread.getLastMemberMessageAt()).isNull();
	}

	@Test
	@DisplayName("없는 대화방에 답장하면 404")
	void reply_rejectsMissingThread() {
		given(letterThreadRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminLetterService.reply(99L, replyRequest("안녕")))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_THREAD_NOT_FOUND);
	}

	@Test
	@DisplayName("여러 명에게 보내면 각자 대화방에 한 통씩")
	void send_deliversToEachRecipient() {
		Member first = member(1L, "포실한 까눌레");
		Member second = member(2L, "바삭한 크루아상");
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(first));
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(second));
		given(letterThreadRepository.findByMemberId(1L)).willReturn(Optional.of(thread(11L, first)));
		given(letterThreadRepository.findByMemberId(2L)).willReturn(Optional.empty());
		given(letterThreadRepository.save(any(LetterThread.class))).willAnswer(inv -> inv.getArgument(0));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		AdminLetterSendResponse response = adminLetterService.send(
			sendRequest(List.of(1L, 2L), "인터뷰 가능하실까요?"));

		assertThat(response.sentCount()).isEqualTo(2);
		assertThat(response.skippedMemberIds()).isEmpty();
		verify(letterMessageRepository, times(2)).save(any(LetterMessage.class));
	}

	@Test
	@DisplayName("탈퇴·없는 회원은 건너뛰고 나머지는 보낸다")
	void send_skipsMissingMembersButDeliversRest() {
		Member alive = member(1L, "포실한 까눌레");
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(alive));
		given(memberRepository.findByIdAndDeletedAtIsNull(404L)).willReturn(Optional.empty());
		given(letterThreadRepository.findByMemberId(1L)).willReturn(Optional.of(thread(11L, alive)));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		AdminLetterSendResponse response = adminLetterService.send(
			sendRequest(List.of(1L, 404L), "인터뷰 가능하실까요?"));

		assertThat(response.sentCount()).isEqualTo(1);
		assertThat(response.skippedMemberIds()).containsExactly(404L);
	}

	@Test
	@DisplayName("같은 사람을 두 번 넣어도 한 통만 간다")
	void send_deduplicatesRecipients() {
		Member alive = member(1L, "포실한 까눌레");
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(alive));
		given(letterThreadRepository.findByMemberId(1L)).willReturn(Optional.of(thread(11L, alive)));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		AdminLetterSendResponse response = adminLetterService.send(
			sendRequest(List.of(1L, 1L, 1L), "인터뷰 가능하실까요?"));

		assertThat(response.sentCount()).isEqualTo(1);
		verify(letterMessageRepository, times(1)).save(any(LetterMessage.class));
	}

	@Test
	@DisplayName("받는 사람이 없으면 거부한다")
	void send_rejectsEmptyRecipients() {
		assertThatThrownBy(() -> adminLetterService.send(sendRequest(List.of(), "안녕")))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_RECIPIENT_EMPTY);

		verify(letterMessageRepository, never()).save(any());
	}

	@Test
	@DisplayName("공백만 있는 편지는 받는 사람을 보기도 전에 거부한다")
	void send_rejectsBlankBody() {
		assertThatThrownBy(() -> adminLetterService.send(sendRequest(List.of(1L), "  \n ")))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_BODY_EMPTY);

		verify(memberRepository, never()).findByIdAndDeletedAtIsNull(anyLong());
	}

	@Test
	@DisplayName("없는 대화방을 읽음 처리하면 404")
	void markAsRead_rejectsMissingThread() {
		given(letterThreadRepository.existsById(99L)).willReturn(false);

		assertThatThrownBy(() -> adminLetterService.markAsRead(99L))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_THREAD_NOT_FOUND);
	}

	private AdminLetterResolveRequest resolveRequest(boolean resolved) {
		AdminLetterResolveRequest request = new AdminLetterResolveRequest();
		ReflectionTestUtils.setField(request, "resolved", resolved);
		return request;
	}

	@Test
	@DisplayName("해결로 닫으면 닫힌 시각이 남는다")
	void resolve_closesThread() {
		LetterThread thread = thread(11L, Member.builder().nickname("포실한 까눌레").build());
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread));

		adminLetterService.resolve(11L, resolveRequest(true));

		assertThat(thread.isResolved()).isTrue();
	}

	@Test
	@DisplayName("두 번 닫아도 처음 닫은 시각을 덮지 않는다")
	void resolve_keepsFirstResolvedAt() {
		LetterThread thread = thread(11L, Member.builder().nickname("포실한 까눌레").build());
		LocalDateTime first = LocalDateTime.of(2026, 9, 1, 9, 0);
		ReflectionTestUtils.setField(thread, "resolvedAt", first);
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread));

		adminLetterService.resolve(11L, resolveRequest(true));

		assertThat(thread.getResolvedAt()).isEqualTo(first);
	}

	@Test
	@DisplayName("잘못 닫았으면 다시 열 수 있다")
	void resolve_reopensThread() {
		LetterThread thread = thread(11L, Member.builder().nickname("포실한 까눌레").build());
		ReflectionTestUtils.setField(thread, "resolvedAt", LocalDateTime.now());
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread));

		adminLetterService.resolve(11L, resolveRequest(false));

		assertThat(thread.isResolved()).isFalse();
	}

	@Test
	@DisplayName("없는 대화방은 해결 처리도 404")
	void resolve_throwsWhenThreadMissing() {
		given(letterThreadRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminLetterService.resolve(99L, resolveRequest(true)))
			.isInstanceOf(LetterException.class);
	}

	@Test
	@DisplayName("답장을 쓰면 닫아 뒀던 대화가 다시 열린다 — 아직 오가는 중이다")
	void reply_reopensResolvedThread() {
		LetterThread thread = thread(11L, Member.builder().nickname("포실한 까눌레").build());
		ReflectionTestUtils.setField(thread, "resolvedAt", LocalDateTime.now().minusDays(2));
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		AdminLetterReplyRequest request = new AdminLetterReplyRequest();
		ReflectionTestUtils.setField(request, "body", "확인해 볼게요");
		adminLetterService.reply(11L, request);

		assertThat(thread.isResolved()).isFalse();
	}

	@Test
	@DisplayName("발송은 알림 한 건으로 남고, 각자 받은 편지가 그 발송에 이어진다")
	void send_recordsOneSendAndLinksLetters() {
		Member first = member(1L, "포실한 까눌레");
		Member second = member(2L, "바삭한 크루아상");
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(first));
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(second));
		given(letterThreadRepository.findByMemberId(anyLong())).willReturn(Optional.empty());
		given(letterThreadRepository.save(any(LetterThread.class))).willAnswer(inv -> inv.getArgument(0));
		List<LetterMessage> saved = new java.util.ArrayList<>();
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> {
			saved.add(inv.getArgument(0));
			return inv.getArgument(0);
		});

		AdminLetterSendRequest request = sendRequest(List.of(1L, 2L), "기록이 어려웠던 이유가 있다면요?");
		ReflectionTestUtils.setField(request, "name", "A. 구경파 · 10/8 설문");
		ReflectionTestUtils.setField(request, "choiceCount", 4);
		adminLetterService.send(request);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordForMembers(captor.capture(), eq(List.of(first, second)), any());
		assertThat(captor.getValue().getType()).isEqualTo(NotificationType.LETTER);
		assertThat(captor.getValue().getChannel()).isEqualTo(NotificationChannel.IN_APP);
		assertThat(captor.getValue().payloadValue("admin.name")).isEqualTo("A. 구경파 · 10/8 설문");
		assertThat(saved).extracting(LetterMessage::getNotificationId).containsOnly(SEND_ID);
		// 푸시를 안 골랐으면 울리지 않는다 — 알림함에만 쌓인다
		verify(eventPublisher, never()).publishEvent(any(PushRequestedEvent.class));
	}

	@Test
	@DisplayName("푸시를 고르면 받은 사람마다 알림이 울린다")
	void send_pushesEachRecipientWhenAsked() {
		Member first = member(1L, "포실한 까눌레");
		Member second = member(2L, "바삭한 크루아상");
		given(memberRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(first));
		given(memberRepository.findByIdAndDeletedAtIsNull(2L)).willReturn(Optional.of(second));
		given(letterThreadRepository.findByMemberId(anyLong())).willReturn(Optional.empty());
		given(letterThreadRepository.save(any(LetterThread.class))).willAnswer(inv -> inv.getArgument(0));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		AdminLetterSendRequest request = sendRequest(List.of(1L, 2L), "잠깐 여쭤봐도 될까요?");
		ReflectionTestUtils.setField(request, "push", true);
		adminLetterService.send(request);

		verify(eventPublisher).publishEvent(PushRequestedEvent.to(SEND_ID, 1L));
		verify(eventPublisher).publishEvent(PushRequestedEvent.to(SEND_ID, 2L));
	}

	@Test
	@DisplayName("문의에 답장하면 그 사람에게 답장 알림이 울린다")
	void reply_pushesInquiryReply() {
		Member member = member(7L, "포실한 까눌레");
		given(letterThreadRepository.findById(11L)).willReturn(Optional.of(thread(11L, member)));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		adminLetterService.reply(11L, replyRequest("고쳐서 다음 업데이트에 넣을게요!"));

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRecorder).recordFor(captor.capture(), eq(member), any());
		assertThat(captor.getValue().getType()).isEqualTo(NotificationType.INQUIRY_REPLY);
		assertThat(captor.getValue().getDeepLink()).isEqualTo("/letters");
		verify(eventPublisher).publishEvent(PushRequestedEvent.to(SEND_ID, 7L));
	}

	@Test
	@DisplayName("집계는 발송 뒤 답장에서 번호를 읽어 사람마다 한 번씩 센다 — 다음 발송 뒤 답장은 빼고")
	void getSendStats_countsChoicesPerPerson() {
		Notification send = withId(Notification.letterSend(NotificationChannel.PUSH, "t", "b", "/letters",
			java.util.Map.of("admin.name", "A 설문", "admin.choiceCount", "4")), SEND_ID);
		given(notificationRepository.findById(SEND_ID)).willReturn(Optional.of(send));

		LetterThread threadA = thread(11L, member(1L, "포실한 까눌레"));
		LetterThread threadB = thread(12L, member(2L, "바삭한 크루아상"));
		LetterThread threadC = thread(13L, member(3L, "촉촉한 마들렌"));
		LetterMessage toA = sent(threadA, 101L, 0);
		LetterMessage toB = sent(threadB, 102L, 0);
		LetterMessage toC = sent(threadC, 103L, 0);
		given(letterMessageRepository.findAllBySendIds(List.of(SEND_ID))).willReturn(List.of(toA, toB, toC));
		LetterMessage laterSend = sent(threadA, 105L, 30);
		ReflectionTestUtils.setField(laterSend, "notificationId", 901L);
		given(letterMessageRepository.findInThreadsSince(anyList(), any())).willReturn(List.of(
			toA, answer(threadA, 201L, "2번입니다!", 1), answer(threadA, 202L, "아 그리고 2", 2), laterSend,
			answer(threadA, 203L, "4", 31),
			toB, answer(threadB, 204L, "음.. 잘 모르겠어요", 1),
			toC));

		AdminLetterSendStatsResponse stats = adminLetterService.getSendStats(SEND_ID);

		assertThat(stats.recipientCount()).isEqualTo(3);
		assertThat(stats.repliedCount()).isEqualTo(2);
		assertThat(stats.counts()).extracting(AdminLetterSendStatsResponse.ChoiceCount::count)
			.containsExactly(0, 1, 0, 0);
		assertThat(stats.unnumberedCount()).isEqualTo(1);
		// 대장에 푸시 줄이 없으면 아무에게도 안 간 것이다 — 받은 3명 모두 안 보냄
		assertThat(stats.pushResult()).isEqualTo(new AdminLetterSendSummaryResponse.PushResult(0, 0, 3));
	}

	@Test
	@DisplayName("푸시 결과는 대장의 성공·실패를 세고, 나머지는 안 보낸 것으로 친다")
	void getSendStats_countsPushResults() {
		Notification send = withId(Notification.letterSend(NotificationChannel.PUSH, "t", "b", "/letters",
			java.util.Map.of("admin.name", "A 설문")), SEND_ID);
		given(notificationRepository.findById(SEND_ID)).willReturn(Optional.of(send));
		LetterThread threadA = thread(11L, member(1L, "포실한 까눌레"));
		LetterThread threadB = thread(12L, member(2L, "바삭한 크루아상"));
		LetterThread threadC = thread(13L, member(3L, "촉촉한 마들렌"));
		given(letterMessageRepository.findAllBySendIds(List.of(SEND_ID)))
			.willReturn(List.of(sent(threadA, 101L, 0), sent(threadB, 102L, 0), sent(threadC, 103L, 0)));
		given(letterMessageRepository.findInThreadsSince(anyList(), any())).willReturn(List.of());
		given(notificationDeliveryRepository.countByNotificationIdsAndChannel(List.of(SEND_ID),
			NotificationChannel.PUSH)).willReturn(List.of(
				new Object[] {SEND_ID, DeliveryStatus.SENT, 1L},
				new Object[] {SEND_ID, DeliveryStatus.FAILED, 1L}));

		AdminLetterSendStatsResponse stats = adminLetterService.getSendStats(SEND_ID);

		assertThat(stats.pushResult()).isEqualTo(new AdminLetterSendSummaryResponse.PushResult(1, 1, 1));
	}

	private LetterMessage sent(LetterThread thread, Long id, int minutes) {
		LetterMessage message = LetterMessage.create(thread, LetterSender.ADMIN, "설문", LetterCategory.OUTREACH);
		ReflectionTestUtils.setField(message, "id", id);
		ReflectionTestUtils.setField(message, "notificationId", SEND_ID);
		ReflectionTestUtils.setField(message, "createdAt", LocalDateTime.of(2026, 10, 8, 14, 0).plusMinutes(minutes));
		return message;
	}

	private LetterMessage answer(LetterThread thread, Long id, String body, int minutes) {
		LetterMessage message = LetterMessage.create(thread, LetterSender.MEMBER, body);
		ReflectionTestUtils.setField(message, "id", id);
		ReflectionTestUtils.setField(message, "createdAt", LocalDateTime.of(2026, 10, 8, 14, 0).plusMinutes(minutes));
		return message;
	}
}
