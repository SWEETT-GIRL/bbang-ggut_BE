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

	/**
	 * 이만큼 조용하면 그 대화는 닫힌 것으로 본다.
	 *
	 * <p>어드민이 해결을 누르는 것만으로는 부족하다. 유형만 눌러보고 말을 안 이어간 사람의
	 * 대화는 답할 것이 없어 아무도 닫지 않는데, 그러면 그 사람은 다시 들어와도 유형을 고를
	 * 기회를 영영 못 받는다 — 말풍선 하나짜리 죽은 대화만 남는다.
	 *
	 * <p>답장을 기다리는 중에 이 기간이 지나 유형을 다시 물어도 크게 해롭지 않다. 대화가
	 * 있으면 입력칸은 잠기지 않아, 묻는 말을 지나쳐 그냥 쓰면 된다.
	 */
	private static final int DORMANT_DAYS = 7;

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
			slice.hasNext(),
			isClosed(thread, LocalDateTime.now()));
	}

	/**
	 * 닫힌 대화인가 — 어드민이 해결로 닫았거나, {@link #DORMANT_DAYS}만큼 조용했거나.
	 *
	 * <p>두 경로를 두는 건 하나만으로는 구멍이 나기 때문이다. 해결 체크만 믿으면 아무도 안
	 * 누른 대화가 영영 안 닫히고, 조용함만 보면 방금 해결된 대화를 일주일이나 열어둔다.
	 */
	private boolean isClosed(LetterThread thread, LocalDateTime now) {
		return thread.isResolved()
			|| thread.getLastMessageAt().isBefore(now.minusDays(DORMANT_DAYS));
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
		// 사용자가 말을 걸었으니 어드민 편지함 목록에 오른다 — 가입 인사만 든 방과 갈리는 지점이다
		thread.touchMember(now);
		// 닫혀 있던 대화라도 사용자가 말을 걸면 다시 열린다 — 그래야 다음 진입에서
		// "어떤 이야기인지 골라주세요"가 또 뜨지 않는다
		thread.reopen();

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
