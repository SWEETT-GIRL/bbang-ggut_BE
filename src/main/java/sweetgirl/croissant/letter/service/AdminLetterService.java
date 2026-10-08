package sweetgirl.croissant.letter.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.repository.NotificationRepository;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.letter.code.LetterErrorCode;
import sweetgirl.croissant.letter.dto.request.AdminLetterReplyRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterResolveRequest;
import sweetgirl.croissant.letter.dto.request.AdminLetterSendRequest;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendStatsResponse;
import sweetgirl.croissant.letter.dto.response.AdminLetterSendSummaryResponse;
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

	/** 편지 알림을 누르면 여는 화면 — 앱은 알림의 link로 간다 */
	private static final String LETTERS_LINK = "/letters";
	private static final String SEND_TITLE = "빵별일지 팀이 편지를 보냈어요 💌";
	private static final String SEND_BODY = "잠깐 시간 괜찮으세요?";
	private static final String REPLY_TITLE = "빵별일지 팀이 답장을 보냈어요 💌";
	private static final String PAYLOAD_NAME = Notification.ADMIN_PAYLOAD_PREFIX + "name";
	private static final String WITHDRAWN_NICKNAME = "탈퇴한 회원";
	private static final String PAYLOAD_CHOICE_COUNT = Notification.ADMIN_PAYLOAD_PREFIX + "choiceCount";

	private final LetterThreadRepository letterThreadRepository;
	private final LetterMessageRepository letterMessageRepository;
	private final MemberRepository memberRepository;
	private final NotificationRepository notificationRepository;
	private final NotificationRecorder notificationRecorder;
	private final ApplicationEventPublisher eventPublisher;

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

		// 답장이 왔다고 울린다 — 없으면 문의한 사람은 편지함을 다시 열기 전까지 답이 온 줄 모른다
		Member member = thread.getMember();
		if (member != null && !member.isDeleted()) {
			Notification recorded = notificationRecorder.recordFor(
				Notification.announcement(NotificationType.INQUIRY_REPLY, NotificationChannel.PUSH,
					REPLY_TITLE, preview(body), LETTERS_LINK),
				member, now);
			eventPublisher.publishEvent(PushRequestedEvent.to(recorded.getId(), member.getId()));
		}

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
		List<Member> recipients = new ArrayList<>();
		List<LetterMessage> letters = new ArrayList<>();
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
			letters.add(letterMessageRepository.save(
				LetterMessage.create(thread, LetterSender.ADMIN, body, LetterCategory.OUTREACH)));
			recipients.add(member);
			sent++;
		}

		if (!recipients.isEmpty()) {
			recordSend(request, body, recipients, letters, now);
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
	/**
	 * 발송 한 번을 알림 한 행으로 남기고, 각자에게 간 편지를 그 행에 잇는다.
	 * 받은 사람 알림함에 한 줄씩 쌓이고, 울리기로 했으면 푸시도 나간다.
	 */
	private void recordSend(AdminLetterSendRequest request, String body, List<Member> recipients,
			List<LetterMessage> letters, LocalDateTime now) {
		Map<String, String> adminPayload = new HashMap<>();
		adminPayload.put(PAYLOAD_NAME, isBlank(request.getName()) ? preview(body) : request.getName().trim());
		if (request.getChoiceCount() != null) {
			adminPayload.put(PAYLOAD_CHOICE_COUNT, String.valueOf(request.getChoiceCount()));
		}
		Notification send = notificationRecorder.recordForMembers(
			Notification.letterSend(
				request.isPush() ? NotificationChannel.PUSH : NotificationChannel.IN_APP,
				isBlank(request.getPushTitle()) ? SEND_TITLE : request.getPushTitle().trim(),
				isBlank(request.getPushBody()) ? SEND_BODY : request.getPushBody().trim(),
				LETTERS_LINK,
				adminPayload),
			recipients, now);
		letters.forEach(letter -> letter.linkSend(send.getId()));
		if (request.isPush()) {
			recipients
				.forEach(member -> eventPublisher.publishEvent(PushRequestedEvent.to(send.getId(), member.getId())));
		}
	}

	/** 보낸 편지 목록 — 최근 발송부터. 받은 사람 수와 답한 사람 수를 같이 준다. */
	public AdminLetterSendSummaryResponse.Page getSends(int page, int size) {
		Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
		Slice<Notification> slice = notificationRepository.findByTypeOrderByIdDesc(NotificationType.LETTER, pageable);
		Map<Long, List<SendReply>> replies = collectReplies(
			slice.getContent().stream().map(Notification::getId).toList());
		List<AdminLetterSendSummaryResponse> sends = slice.getContent().stream()
			.map(send -> {
				List<SendReply> rows = replies.getOrDefault(send.getId(), List.of());
				return new AdminLetterSendSummaryResponse(
					send.getId(),
					send.payloadValue(PAYLOAD_NAME),
					rows.isEmpty() ? "" : preview(rows.get(0).letter().getBody()),
					send.getChannel() == NotificationChannel.PUSH,
					send.getCreatedAt(),
					rows.size(),
					(int)rows.stream().filter(SendReply::replied).count());
			})
			.toList();
		return new AdminLetterSendSummaryResponse.Page(sends, slice.hasNext());
	}

	/**
	 * 발송 한 번의 집계. 답장은 그 사람에게 간 이 편지 뒤로, 다음 발송 편지 전까지 사용자가 쓴 것이다.
	 * 번호는 {@link LetterReplyChoices} 규칙으로 읽고, 한 사람이 같은 번호를 여러 번 써도 한 번만 센다.
	 */
	public AdminLetterSendStatsResponse getSendStats(Long sendId) {
		Notification send = notificationRepository.findById(sendId)
			.filter(n -> n.getType() == NotificationType.LETTER)
			.orElseThrow(() -> new LetterException(LetterErrorCode.LETTER_SEND_NOT_FOUND));
		List<SendReply> rows = collectReplies(List.of(sendId)).getOrDefault(sendId, List.of());
		Integer choiceCount = parseChoiceCount(send.payloadValue(PAYLOAD_CHOICE_COUNT));

		int[] counts = new int[choiceCount == null ? 0 : choiceCount];
		int unnumbered = 0;
		List<AdminLetterSendStatsResponse.Reply> replies = new ArrayList<>();
		List<AdminLetterSendStatsResponse.Recipient> recipients = new ArrayList<>();
		for (SendReply row : rows) {
			LetterThread thread = row.letter().getThread();
			Member member = thread.getMember();
			String nickname = member.isDeleted() ? WITHDRAWN_NICKNAME : member.getNickname();
			recipients.add(new AdminLetterSendStatsResponse.Recipient(
				thread.getId(), member.getId(), nickname, row.replied()));
			if (!row.replied()) {
				continue;
			}
			String text = row.answers().stream()
				.map(LetterMessage::getBody)
				.filter(body -> !body.isBlank())
				.reduce((a, b) -> a + "\n" + b)
				.orElse("(사진)");
			List<Integer> choices = choiceCount == null ? List.of() : LetterReplyChoices.parse(text, choiceCount);
			choices.forEach(choice -> counts[choice - 1]++);
			if (choiceCount != null && choices.isEmpty()) {
				unnumbered++;
			}
			replies.add(new AdminLetterSendStatsResponse.Reply(thread.getId(), member.getId(), nickname,
				choices, text, row.answers().get(0).getCreatedAt()));
		}
		List<AdminLetterSendStatsResponse.ChoiceCount> choiceCounts = new ArrayList<>();
		for (int i = 0; i < counts.length; i++) {
			choiceCounts.add(new AdminLetterSendStatsResponse.ChoiceCount(i + 1, counts[i]));
		}
		return new AdminLetterSendStatsResponse(
			sendId,
			send.payloadValue(PAYLOAD_NAME),
			rows.isEmpty() ? "" : rows.get(0).letter().getBody(),
			send.getChannel() == NotificationChannel.PUSH,
			send.getCreatedAt(),
			choiceCount,
			rows.size(),
			replies.size(),
			choiceCounts,
			unnumbered,
			replies,
			recipients);
	}

	/** 발송 편지 하나와 그 뒤로 사용자가 쓴 편지들 */
	private record SendReply(LetterMessage letter, List<LetterMessage> answers) {
		boolean replied() {
			return !answers.isEmpty();
		}
	}

	/**
	 * 발송마다, 받은 사람마다 그 편지 뒤로 온 사용자 편지를 모은다. 같은 사람에게 다음 발송이
	 * 또 갔으면 거기서 끊는다 — 그 뒤 답장은 다음 발송의 것이다.
	 */
	private Map<Long, List<SendReply>> collectReplies(List<Long> sendIds) {
		if (sendIds.isEmpty()) {
			return Map.of();
		}
		List<LetterMessage> sent = letterMessageRepository.findAllBySendIds(sendIds);
		if (sent.isEmpty()) {
			return Map.of();
		}
		LocalDateTime since = sent.stream().map(LetterMessage::getCreatedAt).min(LocalDateTime::compareTo)
			.orElseThrow();
		List<Long> threadIds = sent.stream().map(m -> m.getThread().getId()).distinct().toList();
		Map<Long, List<LetterMessage>> byThread = new HashMap<>();
		for (LetterMessage m : letterMessageRepository.findInThreadsSince(threadIds, since)) {
			byThread.computeIfAbsent(m.getThread().getId(), k -> new ArrayList<>()).add(m);
		}

		Map<Long, List<SendReply>> result = new HashMap<>();
		for (LetterMessage letter : sent) {
			List<LetterMessage> answers = new ArrayList<>();
			boolean after = false;
			for (LetterMessage m : byThread.getOrDefault(letter.getThread().getId(), List.of())) {
				if (m.getId().equals(letter.getId())) {
					after = true;
					continue;
				}
				if (!after) {
					continue;
				}
				if (m.getSender() == LetterSender.ADMIN && m.getNotificationId() != null) {
					break;
				}
				if (m.getSender() == LetterSender.MEMBER) {
					answers.add(m);
				}
			}
			result.computeIfAbsent(letter.getNotificationId(), k -> new ArrayList<>())
				.add(new SendReply(letter, answers));
		}
		return result;
	}

	private static Integer parseChoiceCount(String value) {
		try {
			return value == null ? null : Integer.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

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
			// 사진만 보낸 편지는 본문이 비어 목록 줄이 텅 빈다 — 사진이라고 적어 둔다
			String body = message.getBody().isBlank() && !message.getImageKeys().isEmpty() ? "(사진)" : message.getBody();
			previews.put(message.getThread().getId(), preview(body));
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
