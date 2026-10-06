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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.external.aws.S3Service;
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
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;

@ExtendWith(MockitoExtension.class)
class LetterServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private LetterThreadRepository letterThreadRepository;

	@Mock
	private LetterMessageRepository letterMessageRepository;

	@Mock
	private MemberRepository memberRepository;

	@Mock
	private S3Service s3Service;

	@Mock
	private AdminReportNotificationPublisher adminReportNotificationPublisher;

	@InjectMocks
	private LetterService letterService;

	private LetterThread thread(Long id) {
		LetterThread thread = LetterThread.create(Member.builder().nickname("포실한 까눌레").build(), LocalDateTime.now());
		ReflectionTestUtils.setField(thread, "id", id);
		return thread;
	}

	/** 마지막 편지 시각과 닫힌 시각을 지정해 만든 대화방. closed 판정을 재는 데 쓴다. */
	private LetterThread thread(Long id, LocalDateTime lastMessageAt, LocalDateTime resolvedAt) {
		LetterThread thread = thread(id);
		ReflectionTestUtils.setField(thread, "lastMessageAt", lastMessageAt);
		ReflectionTestUtils.setField(thread, "resolvedAt", resolvedAt);
		return thread;
	}

	/** 그 대화방의 편지 목록 조회가 빈 슬라이스를 내도록 물려 둔다. */
	private void givenEmptyMessages(Long threadId) {
		given(letterMessageRepository.findByThreadIdOrderByCreatedAtDesc(eq(threadId), any(Pageable.class)))
			.willReturn(new SliceImpl<>(List.of(), PageRequest.of(0, 20), false));
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

	private LetterSendRequest photoRequest(String body, String... imageKeys) {
		LetterSendRequest request = sendRequest(body);
		ReflectionTestUtils.setField(request, "imageKeys", List.of(imageKeys));
		return request;
	}

	@Test
	@DisplayName("사진만 있으면 본문이 비어도 보낸다 — 고른 순서대로")
	void send_acceptsPhotoOnly() {
		String first = "letters/members/" + MEMBER_ID + "/images/a";
		String second = "letters/members/" + MEMBER_ID + "/images/b";
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread(11L)));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		letterService.send(MEMBER_ID, photoRequest(null, first, second));

		ArgumentCaptor<LetterMessage> captor = ArgumentCaptor.forClass(LetterMessage.class);
		verify(letterMessageRepository).save(captor.capture());
		assertThat(captor.getValue().getImageKeys()).containsExactly(first, second);
		assertThat(captor.getValue().getBody()).isEmpty();
	}

	@Test
	@DisplayName("내 편지 사진 폴더가 아닌 키가 하나라도 있으면 거부한다")
	void send_rejectsForeignImageKey() {
		assertThatThrownBy(() -> letterService.send(MEMBER_ID,
			photoRequest("봐주세요", "letters/members/" + MEMBER_ID + "/images/a", "letters/members/8/images/b")))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_INVALID_IMAGE_KEY);

		verify(letterMessageRepository, never()).save(any());
	}

	@Test
	@DisplayName("사진이 아닌 형식은 presigned URL을 주지 않는다")
	void generateImagePresignedUrl_rejectsNonImage() {
		assertThatThrownBy(() -> letterService.generateImagePresignedUrl(MEMBER_ID, "application/pdf"))
			.isInstanceOf(LetterException.class)
			.hasFieldOrPropertyWithValue("errorCode", LetterErrorCode.LETTER_INVALID_IMAGE_MIME_TYPE);
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
	@DisplayName("보낸 편지는 슬랙 알림으로 넘긴다")
	void send_publishesSlackAlert() {
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(0L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread(11L)));
		given(letterMessageRepository.save(any(LetterMessage.class))).willAnswer(inv -> inv.getArgument(0));

		letterService.send(MEMBER_ID, sendRequest("앱이 자꾸 꺼져요", LetterCategory.BUG));

		ArgumentCaptor<LetterMessage> captor = ArgumentCaptor.forClass(LetterMessage.class);
		verify(adminReportNotificationPublisher).publishLetterReceived(captor.capture());
		assertThat(captor.getValue().getBody()).isEqualTo("앱이 자꾸 꺼져요");
	}

	@Test
	@DisplayName("도배로 막힌 편지는 슬랙에 알리지 않는다")
	void send_doesNotAlertWhenRejected() {
		given(letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			eq(MEMBER_ID), eq(LetterSender.MEMBER), any())).willReturn(5L);

		assertThatThrownBy(() -> letterService.send(MEMBER_ID, sendRequest("또 보내요")))
			.isInstanceOf(LetterException.class);

		verify(adminReportNotificationPublisher, never()).publishLetterReceived(any());
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

	@Test
	@DisplayName("오가는 중인 대화는 닫히지 않았다 — 유형을 다시 묻지 않는다")
	void getMyLetters_openConversationIsNotClosed() {
		LetterThread thread = thread(3L, LocalDateTime.now().minusMinutes(5), null);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		givenEmptyMessages(3L);

		assertThat(letterService.getMyLetters(MEMBER_ID, 0, 20).closed()).isFalse();
	}

	@Test
	@DisplayName("어드민이 해결로 닫으면 닫힌 것이다")
	void getMyLetters_resolvedByAdminIsClosed() {
		LetterThread thread = thread(3L, LocalDateTime.now().minusMinutes(5), LocalDateTime.now());
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		givenEmptyMessages(3L);

		assertThat(letterService.getMyLetters(MEMBER_ID, 0, 20).closed()).isTrue();
	}

	@Test
	@DisplayName("아무도 안 닫아도 오래 조용하면 닫힌 것으로 본다 — 유형만 눌러보고 만 사람이 갇히지 않게")
	void getMyLetters_dormantIsClosedEvenWhenNobodyResolved() {
		LetterThread thread = thread(3L, LocalDateTime.now().minusDays(8), null);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		givenEmptyMessages(3L);

		assertThat(letterService.getMyLetters(MEMBER_ID, 0, 20).closed()).isTrue();
	}

	@Test
	@DisplayName("기준일 안쪽으로 조용한 건 아직 열려 있다")
	void getMyLetters_recentlyQuietStaysOpen() {
		LetterThread thread = thread(3L, LocalDateTime.now().minusDays(6), null);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		givenEmptyMessages(3L);

		assertThat(letterService.getMyLetters(MEMBER_ID, 0, 20).closed()).isFalse();
	}

	@Test
	@DisplayName("사용자가 편지를 보내면 그때 어드민 목록에 오른다")
	void send_marksThreadAsInquiry() {
		// 가입 인사만 들어 있던 방 — 사용자가 말을 건 적이 없어 어드민 목록에 안 뜬다
		LetterThread thread = thread(11L);
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class)))
			.willAnswer(invocation -> invocation.getArgument(0));
		assertThat(thread.getLastMemberMessageAt()).isNull();

		letterService.send(MEMBER_ID, sendRequest("오류가 있어요"));

		assertThat(thread.getLastMemberMessageAt()).isNotNull();
	}

	@Test
	@DisplayName("닫혀 있던 대화도 편지를 보내면 다시 열린다")
	void send_reopensResolvedThread() {
		LetterThread thread = thread(11L, LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(20));
		given(letterThreadRepository.findByMemberId(MEMBER_ID)).willReturn(Optional.of(thread));
		given(letterMessageRepository.save(any(LetterMessage.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		letterService.send(MEMBER_ID, sendRequest("아 그리고 하나 더요"));

		assertThat(thread.isResolved()).isFalse();
	}
}
