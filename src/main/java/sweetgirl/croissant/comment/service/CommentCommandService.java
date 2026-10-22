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
		Member writer = memberReader.getMember(memberId);
		BreadRecordComment comment = commentRepository.save(BreadRecordComment.of(record, writer, content));
		List<CommentMention> mentions = new java.util.ArrayList<>();
		for (int i = 0; i < mentioned.size(); i++) {
			MentionRequest mention = request.mentionsOrEmpty().get(i);
			mentions.add(mentionRepository.save(
				CommentMention.of(comment, mentioned.get(i), mention.start(), mention.length())));
		}
		communityNotificationService.notifyRecordComment(comment);
		communityNotificationService.notifyMention(comment, mentioned);
		return CommentResponse.of(comment, mentions, characterFaceReader.faceOf(memberId),
			record.getMember().getId(), memberId);
	}

	/** 내 댓글이거나 내 빵로그의 남의 댓글. 행은 남기고 누가 지웠는지만 적는다. 없거나 이미 지웠으면 404. */
	public void delete(Long memberId, Long commentId) {
		memberReader.requireLoggedIn(memberId);
		BreadRecordComment comment = commentRepository.findActiveByIdWithRecord(commentId)
			.orElseThrow(() -> new CommentException(CommentErrorCode.COMMENT_NOT_FOUND));
		if (comment.getMember().getId().equals(memberId)) {
			comment.delete(BreadRecordComment.DELETED_BY_AUTHOR);
			return;
		}
		if (comment.getRecord().getMember().getId().equals(memberId)) {
			comment.delete(BreadRecordComment.DELETED_BY_RECORD_OWNER);
			return;
		}
		throw new CommentException(CommentErrorCode.COMMENT_DELETE_DENIED);
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
