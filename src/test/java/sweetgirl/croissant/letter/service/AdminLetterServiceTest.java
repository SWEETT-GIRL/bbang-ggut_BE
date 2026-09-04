package sweetgirl.croissant.letter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.AdminLetterReplyRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterResolveRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterSendRequest;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadListResponse;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
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

	@InjectMocks
	private AdminLetterService adminLetterService;

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
}
