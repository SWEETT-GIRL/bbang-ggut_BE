package sweetgirl.croissant.letter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.LetterSendRequest;
import sweetgirl.croissant.letter.dto.response.LetterListResponse;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.exception.LetterException;
import sweetgirl.croissant.letter.repository.LetterMessageRepository;
import sweetgirl.croissant.letter.repository.LetterThreadRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class LetterServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private LetterThreadRepository letterThreadRepository;

	@Mock
	private LetterMessageRepository letterMessageRepository;

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private LetterService letterService;

	private LetterThread thread(Long id) {
		LetterThread thread = LetterThread.create(Member.builder().nickname("포실한 까눌레").build(), LocalDateTime.now());
		ReflectionTestUtils.setField(thread, "id", id);
		return thread;
	}

	private LetterSendRequest sendRequest(String body) {
		return sendRequest(body, null);
	}

	private LetterSendRequest sendRequest(String body, LetterCategory category) {
		LetterSendRequest request = new LetterSendRequest();
		ReflectionTestUtils.setField(request, "body", body);
		ReflectionTestUtils.setField(request, "category", category);
		return request;
	}

	@Test
	@DisplayName("대화방이 없으면 빈 목록 — 404가 아니다")
	void getMyLetters_returnsEmptyWhenNoThread() {
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

		LetterListResponse response = letterService.getMyLetters(MEMBER_ID, 0, 20);

		assertThat(response.messages()).isEmpty();
		assertThat(response.hasNext()).isFalse();
		verify(letterMessageRepository, never()).findByThreadIdOrderByCreatedAtDesc(anyLong(), any());
	}

	@Test
	@DisplayName("size가 상한을 넘으면 50으로 깎인다")
	void getMyLetters_clampsPageSize() {
		LetterThread thread = thread(3L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		Slice<LetterMessage> empty = new SliceImpl<>(List.of(), PageRequest.of(0, 50), false);
		given(letterMessageRepository.findByThreadIdOrderByCreatedAtDesc(eq(3L), any(Pageable.class)))
			.willReturn(empty);

		letterService.getMyLetters(MEMBER_ID, 0, 999);

		org.mockito.ArgumentCaptor<Pageable> captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
		verify(letterMessageRepository).findByThreadIdOrderByCreatedAtDesc(eq(3L), captor.capture());
		assertThat(captor.getValue().getPageSize()).isEqualTo(50);
	}

	@Test
	@DisplayName("첫 편지를 보내면 대화방이 그때 생긴다")
	void send_createsThreadOnFirstLetter() {
		Member member = Member.builder().nickname("포실한 까눌레").build();
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(Optional.of(member));
		given(letterThreadRepository.save(any(LetterThread.class))).willAnswer(inv -> {
			LetterThread created = inv.getArgument(0);
			ReflectionTestUtils.setField(created, "id", 11L);
			return created;
		});
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		letterService.send(MEMBER_ID, sendRequest("인터뷰 가능해요"));

		verify(letterThreadRepository).save(any(LetterThread.class));
		verify(letterMessageRepository).save(any(LetterMessage.class));
	}

	@Test
	@DisplayName("답장을 보내면 받은 편지가 전부 읽음 처리된다")
	void send_marksAdminLettersRead() {
		LetterThread thread = thread(11L);
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		letterService.send(MEMBER_ID, sendRequest("네 좋아요"));

		verify(letterMessageRepository).markAllAsRead(eq(11L), eq(LetterSender.ADMIN), any(LocalDateTime.class));
	}

	@Test
	@DisplayName("공백만 있는 편지는 거부한다")
	void send_rejectsBlankBody() {
		assertThatThrownBy(() -> letterService.send(MEMBER_ID, sendRequest("   ")))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_BODY_EMPTY);

		verify(letterMessageRepository, never()).save(any());
	}

	@Test
	@DisplayName("1분에 정해진 통수를 넘기면 막는다")
	void send_rejectsWhenTooFrequent() {
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(5L);

		assertThatThrownBy(() -> letterService.send(MEMBER_ID, sendRequest("도배")))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_TOO_FREQUENT);

		verify(letterMessageRepository, never()).save(any());
	}

	@Test
	@DisplayName("탈퇴한 회원은 대화방을 못 만든다")
	void send_rejectsWithdrawnMember() {
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());
		given(memberRepository.findByIdAndDeletedAtIsNull(MEMBER_ID)).willReturn(Optional.empty());

		assertThatThrownBy(() -> letterService.send(MEMBER_ID, sendRequest("안녕하세요")))
			.isInstanceOf(MemberException.class);
	}

	@Test
	@DisplayName("고른 문의 유형이 메시지에 붙어 저장된다")
	void send_persistsChosenCategory() {
		LetterThread thread = thread(11L);
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		letterService.send(MEMBER_ID, sendRequest("앱이 자꾸 꺼져요", LetterCategory.BUG));

		org.mockito.ArgumentCaptor<LetterMessage> captor = org.mockito.ArgumentCaptor.forClass(LetterMessage.class);
		verify(letterMessageRepository).save(captor.capture());
		assertThat(captor.getValue().getCategory()).isEqualTo(LetterCategory.BUG);
	}

	@Test
	@DisplayName("유형 없이 보내도 받는다 — 이어 쓰는 메시지가 그렇다")
	void send_allowsNullCategory() {
		LetterThread thread = thread(11L);
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		letterService.send(MEMBER_ID, sendRequest("아 그리고 하나 더요"));

		org.mockito.ArgumentCaptor<LetterMessage> captor = org.mockito.ArgumentCaptor.forClass(LetterMessage.class);
		verify(letterMessageRepository).save(captor.capture());
		assertThat(captor.getValue().getCategory()).isNull();
	}

	@Test
	@DisplayName("대화방이 없으면 읽음 처리는 아무 것도 안 한다")
	void markAsRead_noopWhenNoThread() {
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.empty());

		letterService.markAsRead(MEMBER_ID);

		verify(letterMessageRepository, never()).markAllAsRead(anyLong(), any(), any());
	}
}
