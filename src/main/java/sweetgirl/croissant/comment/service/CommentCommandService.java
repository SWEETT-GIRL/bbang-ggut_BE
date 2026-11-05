package sweetgirl.croissant.comment.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.service.BreadRecordVisibilityPolicy;
import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.dto.request.CommentCreateRequest;
import sweetgirl.croissant.comment.dto.request.MentionRequest;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.comment.repository.CommentMentionRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@Service
@RequiredArgsConstructor
@Transactional
public class CommentCommandService {

	/** 도배 제한 — 30초에 3개. */
	static final int BURST_LIMIT = 3;
	static final int BURST_WINDOW_SECONDS = 30;

	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordCommentRepository commentRepository;
	private final CommentMentionRepository mentionRepository;
	private final BreadRecordVisibilityPolicy visibilityPolicy;
	private final CommentContentPolicy contentPolicy;
	private final CommunityAgreementService communityAgreementService;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final SocialMemberReader socialMemberReader;
	private final MemberBlockRepository memberBlockRepository;
	private final CharacterFaceReader characterFaceReader;
	private final CommunityNotificationService communityNotificationService;

	/**
	 * 공개 빵로그에만. 게스트 403 MEMBER403_GUEST → 작성 제한 · 정지 403 → 규칙 동의 전 403 COMMUNITY_AGREEMENT_REQUIRED →
	 * 볼 수 없는 기록 403 → 내용(길이 · 금칙어 · 링크 · 전화번호 400) → 도배 429 TOO_FAST → 멘션(3명, 차단 · 탈퇴 400).
	 */
	public CommentResponse create(Long memberId, Long recordId, CommentCreateRequest request) {
		memberReader.requireLoggedIn(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		communityAgreementService.validateAgreed(memberId);
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		visibilityPolicy.validateCanView(memberId, record);
		if (!record.isPublic() || record.isHidden()) {
			throw new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_VISIBLE);
		}
		String content = request.content().strip();
		contentPolicy.validateComment(content);
		LocalDateTime now = LocalDateTime.now();
		if (commentRepository.countByMemberIdAndCreatedAtAfter(memberId,
			now.minusSeconds(BURST_WINDOW_SECONDS)) >= BURST_LIMIT) {
			throw new CommentException(CommentErrorCode.COMMENT_TOO_FAST);
		}
		List<Member> mentioned = validateMentions(memberId, record.getMember().getId(), content,
			request.mentionsOrEmpty());
		BreadRecordComment parent = request.parentCommentId() == null ? null
			: commentRepository.findReplyTarget(request.parentCommentId(), record.getId())
				.orElseThrow(() -> new CommentException(CommentErrorCode.COMMENT_NOT_FOUND));
		Member writer = memberReader.getMember(memberId);
		BreadRecordComment comment = commentRepository.save(parent == null
			? BreadRecordComment.of(record, writer, content)
			: BreadRecordComment.reply(record, writer, content, parent));
		List<CommentMention> mentions = new java.util.ArrayList<>();
		for (int i = 0; i < mentioned.size(); i++) {
			MentionRequest mention = request.mentionsOrEmpty().get(i);
			mentions.add(mentionRepository.save(
				CommentMention.of(comment, mentioned.get(i), mention.start(), mention.length())));
		}
		communityNotificationService.notifyRecordComment(comment);
		if (parent != null) {
			// 답글은 내가 고른 댓글의 작성자에게 간다 — 깊이 1 로 접혀 부모가 바뀌어도 알림은 고른 사람에게
			communityNotificationService.notifyCommentReply(comment, parent.getMember());
		}
		communityNotificationService.notifyMention(comment, mentioned);
		return CommentResponse.of(comment, mentions, characterFaceReader.faceOf(memberId),
			record.getMember().getId(), memberId);
	}

	/**
	 * 내 댓글이거나 내 빵로그의 남의 댓글. 행은 남기고 누가 지웠는지만 적는다. 없거나 이미 지웠으면 404.
	 * 최상위 댓글을 지우면 그 답글도 같은 사유로 같이 지운다(부모 없는 답글이 남지 않게).
	 */
	public void delete(Long memberId, Long commentId) {
		memberReader.requireLoggedIn(memberId);
		BreadRecordComment comment = commentRepository.findActiveByIdWithRecord(commentId)
			.orElseThrow(() -> new CommentException(CommentErrorCode.COMMENT_NOT_FOUND));
		String deletedBy;
		if (comment.getMember().getId().equals(memberId)) {
			deletedBy = BreadRecordComment.DELETED_BY_AUTHOR;
		} else if (comment.getRecord().getMember().getId().equals(memberId)) {
			deletedBy = BreadRecordComment.DELETED_BY_RECORD_OWNER;
		} else {
			throw new CommentException(CommentErrorCode.COMMENT_DELETE_DENIED);
		}
		comment.delete(deletedBy);
		// 지운 글이 알림함에 미리보기로 남지 않게 — 댓글 · 멘션 · 답글 알림을 거둔다
		communityNotificationService.retractCommentNotifications(comment.getId());
		if (!comment.isReply()) {
			for (BreadRecordComment reply : commentRepository.findActiveReplies(comment.getId())) {
				reply.delete(deletedBy);
				communityNotificationService.retractCommentNotifications(reply.getId());
			}
		}
	}

	/** 최대 3명 · 같은 사람 한 번 · 본문 안 오프셋 · 상대가 될 수 있는 사람(탈퇴 · 게스트 · 봇 아님) · 나 · 빵로그 작성자와 차단 관계 아님. */
	private List<Member> validateMentions(Long memberId, Long recordAuthorId, String content,
			List<MentionRequest> requests) {
		if (requests.size() > BreadRecordComment.MAX_MENTIONS) {
			throw new CommentException(CommentErrorCode.COMMENT_MENTION_LIMIT);
		}
		Set<Long> seen = new HashSet<>();
		List<Member> mentioned = new java.util.ArrayList<>();
		for (MentionRequest request : requests) {
			if (!seen.add(request.memberId()) || request.start() + request.length() > content.length()) {
				throw new CommentException(CommentErrorCode.COMMENT_MENTION_INVALID);
			}
			Member target = socialMemberReader.findSocial(request.memberId())
				.orElseThrow(() -> new CommentException(CommentErrorCode.COMMENT_MENTION_INVALID));
			if (memberBlockRepository.existsBetween(memberId, target.getId())
				|| memberBlockRepository.existsBetween(recordAuthorId, target.getId())) {
				throw new CommentException(CommentErrorCode.COMMENT_MENTION_INVALID);
			}
			mentioned.add(target);
		}
		return mentioned;
	}
}
