package sweetgirl.croissant.letter.service;

import java.time.LocalDateTime;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.LetterSendRequest;
import sweetgirl.croissant.letter.dto.response.LetterListResponse;
import sweetgirl.croissant.letter.dto.response.LetterMessageResponse;
import sweetgirl.croissant.letter.dto.response.LetterUnreadCountResponse;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.exception.LetterException;
import sweetgirl.croissant.letter.repository.LetterMessageRepository;
import sweetgirl.croissant.letter.repository.LetterThreadRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 사용자 쪽 편지함 — 개발자와 주고받은 대화를 보고, 답장하고, 먼저 말을 건다.
 *
 * <p>대화방은 회원당 하나이고 <b>필요할 때 생긴다.</b> 가입 시점에 미리 만들면 편지 한 통 안
 * 오간 빈 방이 회원 수만큼 쌓인다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LetterService {

	private static final int MAX_PAGE_SIZE = 50;

	/**
	 * 도배 방지 — 1분에 이 통수까지.
	 *
	 * <p>간격이 아니라 개수로 막는다. 문의 유형을 고르면 그게 한 통으로 나가고 본문이 곧바로
	 * 뒤따르는데, "직전 한 통에서 N초"로 재면 그 정상적인 흐름이 걸려버린다.
	 */
	private static final int SEND_LIMIT_PER_MINUTE = 5;

	private final LetterThreadRepository letterThreadRepository;
	private final LetterMessageRepository letterMessageRepository;
	private final MemberRepository memberRepository;

	/**
	 * 내 편지 목록 — 최신순. 앱이 뒤집힌 목록(inverted)으로 그리고, 위로 당기면 옛 편지를 더 받는다.
	 *
	 * <p>대화방이 없으면 빈 목록이다. "아직 편지가 없다"와 "방이 없다"를 앱이 구분할 이유가 없어
	 * 404를 내지 않는다.
	 */
	public LetterListResponse getMyLetters(Long memberId, int page, int size) {
		LetterThread thread = letterThreadRepository.findByMemberId(memberId).orElse(null);
		if (thread == null) {
			return LetterListResponse.empty();
		}

		Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Slice<LetterMessage> slice = letterMessageRepository
			.findByThreadIdOrderByCreatedAtDesc(thread.getId(), pageable);

		return new LetterListResponse(
			slice.getContent().stream().map(LetterMessageResponse::from).toList(),
			slice.hasNext());
	}

	/** 벨 배지용 — 개발자가 보낸 편지 중 안 읽은 것. */
	public LetterUnreadCountResponse getUnreadCount(Long memberId) {
		return new LetterUnreadCountResponse(
			letterMessageRepository.countByThreadMemberIdAndSenderAndReadAtIsNull(memberId, LetterSender.ADMIN));
	}

	/**
	 * 편지를 보낸다. 대화방이 없으면 이때 만든다.
	 *
	 * <p>보내는 김에 받은 편지를 전부 읽음으로 넘긴다 — 답장을 썼다는 건 읽었다는 뜻이라,
	 * 답장하고도 벨에 점이 남아 있으면 이상하다.
	 *
	 * <p>읽음 처리가 <b>맨 마지막</b>인 건 순서 문제다. 벌크 UPDATE라 영속성 컨텍스트를 비우는데,
	 * 그 뒤에 스레드나 메시지를 만지면 이미 준영속이 된 엔티티를 건드리게 된다.
	 */
	@Transactional
	public LetterMessageResponse send(Long memberId, LetterSendRequest request) {
		String body = request.getBody() == null ? "" : request.getBody().trim();
		if (body.isEmpty()) {
			throw new LetterException(LetterErrorCode.LETTER_BODY_EMPTY);
		}

		LocalDateTime now = LocalDateTime.now();
		if (letterMessageRepository.countByThreadMemberIdAndSenderAndCreatedAtAfter(
			memberId, LetterSender.MEMBER, now.minusMinutes(1)) >= SEND_LIMIT_PER_MINUTE) {
			throw new LetterException(LetterErrorCode.LETTER_TOO_FREQUENT);
		}

		LetterThread thread = letterThreadRepository.findByMemberId(memberId)
			.orElseGet(() -> createThread(memberId, now));
		thread.touch(now);

		LetterMessage saved = letterMessageRepository.save(
			LetterMessage.create(thread, LetterSender.MEMBER, body, request.getCategory()));
		LetterMessageResponse response = LetterMessageResponse.from(saved);

		letterMessageRepository.markAllAsRead(thread.getId(), LetterSender.ADMIN, now);
		return response;
	}

	/** 편지함을 열었으니 받은 편지는 전부 읽음. 대화방이 없으면 읽을 것도 없다. */
	@Transactional
	public void markAsRead(Long memberId) {
		letterThreadRepository.findByMemberId(memberId).ifPresent(
			thread -> letterMessageRepository.markAllAsRead(thread.getId(), LetterSender.ADMIN, LocalDateTime.now()));
	}

	private LetterThread createThread(Long memberId, LocalDateTime now) {
		Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		return letterThreadRepository.save(LetterThread.create(member, now));
	}
}
