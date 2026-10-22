package sweetgirl.croissant.comment.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.comment.code.CommentErrorCode;
import sweetgirl.croissant.comment.dto.response.CommunityAgreementResponse;
import sweetgirl.croissant.comment.exception.CommentException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

/**
 * 커뮤니티 규칙 동의 — 첫 댓글 · 첫 공개 전환 전에 앱이 받는다(피그마 08-D1 · D2). 규칙 버전이 오르면 다시 받는다.
 * 애플 1.2 "만들기 전 금지 콘텐츠 · 무관용 동의"의 근거다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CommunityAgreementService {

	/** 규칙 문구가 바뀌면 올린다. 올리면 모두가 다시 동의해야 한다. */
	public static final String CURRENT_VERSION = "2026-10-21";

	private final MemberReader memberReader;

	@Transactional(readOnly = true)
	public CommunityAgreementResponse get(Long memberId) {
		return CommunityAgreementResponse.from(memberReader.getMember(memberId), CURRENT_VERSION);
	}

	public CommunityAgreementResponse agree(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		Member member = memberReader.getMember(memberId);
		member.agreeToCommunityRules(CURRENT_VERSION, LocalDateTime.now());
		return CommunityAgreementResponse.from(member, CURRENT_VERSION);
	}

	@Transactional(readOnly = true)
	public void validateAgreed(Long memberId) {
		if (!memberReader.getMember(memberId).hasAgreedToCommunityRules(CURRENT_VERSION)) {
			throw new CommentException(CommentErrorCode.COMMUNITY_AGREEMENT_REQUIRED);
		}
	}
}
