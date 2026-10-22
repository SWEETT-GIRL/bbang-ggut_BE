package sweetgirl.croissant.comment.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.service.ReportedTargetReader;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.service.BreadRecordVisibilityPolicy;
import sweetgirl.croissant.comment.dto.response.CommentResponse;
import sweetgirl.croissant.comment.dto.response.MentionCandidateResponse;
import sweetgirl.croissant.comment.entity.BreadRecordComment;
import sweetgirl.croissant.comment.entity.CommentMention;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.comment.repository.CommentMentionRepository;
import sweetgirl.croissant.follow.entity.MemberFollow;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.util.NicknameNormalizer;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentQueryService {

	static final int MAX_PAGE_SIZE = 50;
	static final int MENTION_CANDIDATE_LIMIT = 20;
	private static final int RELATION_SCAN_LIMIT = 500;

	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordCommentRepository commentRepository;
	private final CommentMentionRepository mentionRepository;
	private final BreadRecordVisibilityPolicy visibilityPolicy;
	private final CharacterFaceReader characterFaceReader;
	private final MemberFollowRepository memberFollowRepository;
	private final MemberRepository memberRepository;
	private final MemberReader memberReader;
	private final ReportedTargetReader reportedTargetReader;

	/** 오래된 순. 기록을 볼 수 있어야 댓글도 본다. */
	public SliceResponse<CommentResponse> getComments(Long viewerId, Long recordId, int page, int size) {
		BreadRecord record = visibleRecord(viewerId, recordId);
		Slice<BreadRecordComment> comments = commentRepository.findVisibleByRecordId(record.getId(), viewerId,
			PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
		// 신고한 댓글은 신고자 화면에서 바로 빠진다
		List<BreadRecordComment> visible = ReportedTargetReader.without(comments.getContent(),
			BreadRecordComment::getId,
			reportedTargetReader.reportedIds(viewerId, AbuseReportTargetType.COMMENT));
		return new SliceResponse<>(toResponses(viewerId, record, visible), comments.hasNext());
	}

	/** 상세에 넣는 첫 댓글 1개. 없으면 null. */
	public CommentResponse firstComment(Long viewerId, BreadRecord record) {
		return commentRepository.findFirstVisibleByRecordId(record.getId(), viewerId)
			.filter(
				comment -> !reportedTargetReader.hasReported(viewerId, AbuseReportTargetType.COMMENT, comment.getId()))
			.map(comment -> toResponses(viewerId, record, List.of(comment)).get(0))
			.orElse(null);
	}

	/**
	 * 멘션 후보 — 팔로잉 · 팔로워 · 이 빵로그에 댓글 단 사람 중 앞글자 일치. 차단 관계 · 탈퇴 · 게스트는 빠진다.
	 * 관계는 가까운 순(FOLLOWING → FOLLOWER → COMMENTER)으로 하나만 붙인다.
	 */
	public List<MentionCandidateResponse> mentionCandidates(Long viewerId, Long recordId, String query) {
		memberReader.requireLoggedIn(viewerId);
		BreadRecord record = visibleRecord(viewerId, recordId);
		String prefix = NicknameNormalizer.normalize(query);
		Map<Long, String> relationById = new LinkedHashMap<>();
		Pageable scan = PageRequest.of(0, RELATION_SCAN_LIMIT);
		memberFollowRepository.findFollowings(viewerId, viewerId, null, scan).getContent().stream()
			.map(MemberFollow::getFollowee).forEach(member -> relationById.putIfAbsent(member.getId(), "FOLLOWING"));
		memberFollowRepository.findFollowers(viewerId, viewerId, null, scan).getContent().stream()
			.map(MemberFollow::getFollower).forEach(member -> relationById.putIfAbsent(member.getId(), "FOLLOWER"));
		commentRepository.findCommenterIds(record.getId())
			.forEach(memberId -> relationById.putIfAbsent(memberId, "COMMENTER"));
		relationById.remove(viewerId);
		if (relationById.isEmpty()) {
			return List.of();
		}
		List<Member> people = memberRepository.findSocialByIdIn(relationById.keySet()).stream()
			.filter(member -> prefix.isEmpty() || member.getNicknameNormalized().startsWith(prefix))
			.sorted((first, second) -> first.getNicknameNormalized().compareTo(second.getNicknameNormalized()))
			.limit(MENTION_CANDIDATE_LIMIT)
			.toList();
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(people.stream().map(Member::getId).toList());
		return people.stream()
			.map(member -> MentionCandidateResponse.of(member, faces.getOrDefault(member.getId(), CharacterFace.NONE),
				relationById.get(member.getId())))
			.toList();
	}

	private BreadRecord visibleRecord(Long viewerId, Long recordId) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		visibilityPolicy.validateCanView(viewerId, record);
		return record;
	}

	private List<CommentResponse> toResponses(Long viewerId, BreadRecord record, List<BreadRecordComment> comments) {
		if (comments.isEmpty()) {
			return List.of();
		}
		List<Long> commentIds = comments.stream().map(BreadRecordComment::getId).toList();
		Map<Long, List<CommentMention>> mentionsByComment = mentionRepository.findAllByIdCommentIdIn(commentIds)
			.stream()
			.collect(Collectors.groupingBy(mention -> mention.getId().getCommentId()));
		Set<Long> memberIds = comments.stream().map(comment -> comment.getMember().getId()).collect(Collectors.toSet());
		Map<Long, CharacterFace> faces = characterFaceReader.facesOf(memberIds);
		Long recordAuthorId = record.getMember().getId();
		List<CommentResponse> responses = new ArrayList<>();
		for (BreadRecordComment comment : comments) {
			responses.add(CommentResponse.of(comment,
				mentionsByComment.getOrDefault(comment.getId(), List.of()),
				faces.getOrDefault(comment.getMember().getId(), CharacterFace.NONE),
				recordAuthorId, viewerId));
		}
		return responses;
	}
}
