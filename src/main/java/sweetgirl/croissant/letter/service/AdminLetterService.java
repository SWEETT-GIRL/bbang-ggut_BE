package sweetgirl.croissant.letter.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.AdminLetterReplyRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterResolveRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterSendRequest;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadDetailResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadListResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterThreadResponse;
import sweetgirl.croissant.letter.dto.response.LetterMessageResponse;
import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.entity.LetterThread;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;
import sweetgirl.croissant.letter.exception.LetterException;
import sweetgirl.croissant.letter.repository.LetterMessageRepository;
import sweetgirl.croissant.letter.repository.LetterThreadRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

/**
 * 어드민 쪽 편지함 — 대화 목록을 보고, 답장하고, 지정한 사람들에게 편지를 보낸다.
 *
 * <p>"전체에게"는 일부러 없다. 편지는 답장이 돌아오는 통로라서 전체 발송은 감당할 수 없는
 * 답장을 부른다 — 그런 소식은 알림(공지) 쪽이 맡는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminLetterService {

	private static final int MAX_PAGE_SIZE = 50;

	/** 목록 한 줄에 실리는 미리보기 길이. 대화 본문을 통째로 실어 보낼 이유가 없다. */
	private static final int PREVIEW_LENGTH = 60;

	private final LetterThreadRepository letterThreadRepository;
	private final LetterMessageRepository letterMessageRepository;
	private final MemberRepository memberRepository;

	/**
	 * 대화 목록 — 최근순.
	 *
	 * <p>마지막 편지와 미읽음 수는 스레드마다 물으면 20줄에 40번이 된다. 이번 페이지의 id를
	 * 모아 두 번만 물어 붙인다.
	 */
	public AdminLetterThreadListResponse getThreads(int page, int size) {
		Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Slice<LetterThread> slice = letterThreadRepository.findAllForAdmin(pageable);

		List<Long> threadIds = slice.getContent().stream().map(LetterThread::getId).toList();
		Map<Long, String> previews = loadPreviews(threadIds);
		Map<Long, Long> unreadCounts = loadUnreadCounts(threadIds);
		Map<Long, LetterCategory> categories = loadCategories(threadIds);

		List<AdminLetterThreadResponse> threads = slice.getContent().stream()
			.map(thread -> {
				Member member = thread.getMember();
				return new AdminLetterThreadResponse(
					thread.getId(),
					member.getId(),
					member.getNickname(),
					member.getDeletedAt() != null,
					previews.getOrDefault(thread.getId(), ""),
					thread.getLastMessageAt(),
					unreadCounts.getOrDefault(thread.getId(), 0L),
					categories.get(thread.getId()),
					thread.isResolved());
			})
			.toList();

		return new AdminLetterThreadListResponse(threads, slice.hasNext());
	}

	/** 대화 하나. 사용자 쪽과 같은 최신순이다. */
	public AdminLetterThreadDetailResponse getThread(Long threadId, int page, int size) {
		LetterThread thread = letterThreadRepository.findById(threadId)
			.orElseThrow(() -> new LetterException(LetterErrorCode.LETTER_THREAD_NOT_FOUND));

		Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Slice<LetterMessage> slice = letterMessageRepository
			.findByThreadIdOrderByCreatedAtDesc(threadId, pageable);

		Member member = thread.getMember();
		return new AdminLetterThreadDetailResponse(
			thread.getId(),
			member.getId(),
			member.getNickname(),
			member.getDeletedAt() != null,
			slice.getContent().stream().map(LetterMessageResponse::from).toList(),
			slice.hasNext(),
			thread.isResolved());
	}

	/**
	 * 답장. 사용자 쪽 {@code send}와 같은 이유로 읽음 처리를 맨 마지막에 둔다 —
	 * 벌크 UPDATE가 영속성 컨텍스트를 비우기 때문이다.
	 */
	@Transactional
	public LetterMessageResponse reply(Long threadId, AdminLetterReplyRequest request) {
		String body = normalize(request.getBody());

		LetterThread thread = letterThreadRepository.findById(threadId)
			.orElseThrow(() -> new LetterException(LetterErrorCode.LETTER_THREAD_NOT_FOUND));

		LocalDateTime now = LocalDateTime.now();
		thread.touch(now);
		// 답장을 썼다는 건 아직 오가는 중이라는 뜻이다 — 닫아 뒀더라도 다시 연다.
		// 닫는 건 해결을 누를 때뿐이어야, 사용자 화면에서 유형을 다시 묻는 시점이 어긋나지 않는다
		thread.reopen();

		LetterMessage saved = letterMessageRepository.save(
			LetterMessage.create(thread, LetterSender.ADMIN, body));
		LetterMessageResponse response = LetterMessageResponse.from(saved);

		letterMessageRepository.markAllAsRead(threadId, LetterSender.MEMBER, now);
		return response;
	}

	/**
	 * 지정한 사람들에게 같은 편지를 한 통씩. 각자 자기 대화방에 꽂히고 답장도 따로 온다.
	 *
	 * <p>없는 회원이나 탈퇴한 회원은 건너뛰고 <b>나머지는 보낸다.</b> id 하나가 썩었다고 서른
	 * 명에게 갈 편지를 통째로 막을 이유가 없다 — 대신 누가 빠졌는지는 응답으로 돌려준다.
	 */
	@Transactional
	public AdminLetterSendResponse send(AdminLetterSendRequest request) {
		String body = normalize(request.getBody());

		List<Long> memberIds = request.getMemberIds() == null
			? List.of()
			: new ArrayList<>(new LinkedHashSet<>(request.getMemberIds()));
		if (memberIds.isEmpty()) {
			throw new LetterException(LetterErrorCode.LETTER_RECIPIENT_EMPTY);
		}

		LocalDateTime now = LocalDateTime.now();
		List<Long> skipped = new ArrayList<>();
		int sent = 0;

		for (Long memberId : memberIds) {
			Member member = memberRepository.findByIdAndDeletedAtIsNull(memberId).orElse(null);
			if (member == null) {
				skipped.add(memberId);
				continue;
			}
			LetterThread thread = letterThreadRepository.findByMemberId(memberId)
				.orElseGet(() -> letterThreadRepository.save(LetterThread.create(member, now)));
			thread.touch(now);
			thread.reopen();
			// 우리가 먼저 건 말은 OUTREACH — 사용자가 올린 문의와 목록에서 갈려 보여야 한다
			letterMessageRepository.save(
				LetterMessage.create(thread, LetterSender.ADMIN, body, LetterCategory.OUTREACH));
			sent++;
		}

		return new AdminLetterSendResponse(sent, skipped);
	}

	/**
	 * 해결로 닫거나 다시 연다.
	 *
	 * <p>닫으면 사용자가 다음에 편지함에 들어올 때 "어떤 이야기인지 골라주세요"를 다시 본다 —
	 * 그 건은 끝났고 다음은 새 용건이라는 뜻이다. 사용자가 편지를 한 통이라도 더 보내면
	 * 자동으로 다시 열린다.
	 */
	@Transactional
	public void resolve(Long threadId, AdminLetterResolveRequest request) {
		LetterThread thread = letterThreadRepository.findById(threadId)
			.orElseThrow(() -> new LetterException(LetterErrorCode.LETTER_THREAD_NOT_FOUND));

		if (request.isResolved()) {
			thread.resolve(LocalDateTime.now());
		} else {
			thread.reopen();
		}
	}

	/** 대화를 열었으니 사용자가 보낸 편지는 전부 읽음. */
	@Transactional
	public void markAsRead(Long threadId) {
		if (!letterThreadRepository.existsById(threadId)) {
			throw new LetterException(LetterErrorCode.LETTER_THREAD_NOT_FOUND);
		}
		letterMessageRepository.markAllAsRead(threadId, LetterSender.MEMBER, LocalDateTime.now());
	}

	private Map<Long, String> loadPreviews(List<Long> threadIds) {
		if (threadIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> previews = new HashMap<>();
		for (LetterMessage message : letterMessageRepository.findLatestPerThread(threadIds)) {
			previews.put(message.getThread().getId(), preview(message.getBody()));
		}
		return previews;
	}

	/** 스레드마다 "무슨 건이었는지" — 가장 최근에 유형이 붙은 사용자 메시지에서 가져온다. */
	private Map<Long, LetterCategory> loadCategories(List<Long> threadIds) {
		if (threadIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, LetterCategory> categories = new HashMap<>();
		for (LetterMessage message : letterMessageRepository.findLatestCategorizedPerThread(threadIds)) {
			categories.put(message.getThread().getId(), message.getCategory());
		}
		return categories;
	}

	private Map<Long, Long> loadUnreadCounts(List<Long> threadIds) {
		if (threadIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Long> counts = new HashMap<>();
		for (Object[] row : letterMessageRepository.countUnreadPerThread(threadIds, LetterSender.MEMBER)) {
			counts.put((Long)row[0], (Long)row[1]);
		}
		return counts;
	}

	private static String preview(String body) {
		String flattened = body.replaceAll("\\s+", " ").trim();
		return flattened.length() <= PREVIEW_LENGTH
			? flattened
			: flattened.substring(0, PREVIEW_LENGTH) + "…";
	}

	private static String normalize(String body) {
		String trimmed = body == null ? "" : body.trim();
		if (trimmed.isEmpty()) {
			throw new LetterException(LetterErrorCode.LETTER_BODY_EMPTY);
		}
		return trimmed;
	}
}
