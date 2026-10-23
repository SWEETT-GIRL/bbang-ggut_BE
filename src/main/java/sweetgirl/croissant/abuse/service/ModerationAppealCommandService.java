package sweetgirl.croissant.abuse.service;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.code.AbuseReportErrorCode;
import sweetgirl.croissant.abuse.dto.request.ModerationAppealCreateRequest;
import sweetgirl.croissant.abuse.dto.response.ModerationAppealCreateResponse;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.abuse.entity.ModerationAppeal;
import sweetgirl.croissant.abuse.enums.AbuseReportAction;
import sweetgirl.croissant.abuse.exception.AbuseReportException;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.abuse.repository.ModerationAppealRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@RequiredArgsConstructor
@Transactional
public class ModerationAppealCommandService {

	private static final long APPEAL_PERIOD_DAYS = 30L;
	private static final Set<AbuseReportAction> APPEALABLE_ACTIONS = Set.of(
		AbuseReportAction.HIDE_REVIEW,
		AbuseReportAction.DELETE_REVIEW,
		AbuseReportAction.RESET_PROFILE,
		AbuseReportAction.RESTRICT_MEMBER,
		AbuseReportAction.SUSPEND_MEMBER,
		// 빵로그 · 댓글 숨김 · 삭제 — 자동 숨김 알림이 "이의제기" 로 이어지는 입구(10/22 리뷰에서 빠져 있던 것)
		AbuseReportAction.HIDE_RECORD,
		AbuseReportAction.HIDE_COMMENT,
		AbuseReportAction.DELETE_COMMENT);

	private final MemberReader memberReader;
	private final ModerationActionRepository moderationActionRepository;
	private final ModerationAppealRepository moderationAppealRepository;

	public ModerationAppealCreateResponse createAppeal(
			Long memberId,
			ModerationAppealCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		ModerationAction action = moderationActionRepository.findByIdWithMemberAndReport(request.actionId())
			.orElseThrow(() -> new AbuseReportException(AbuseReportErrorCode.APPEAL_ACTION_NOT_FOUND));

		validateAppealableAction(action, member);
		if (moderationAppealRepository.existsByModerationActionIdAndMemberId(action.getId(), member.getId())) {
			throw new AbuseReportException(AbuseReportErrorCode.APPEAL_ALREADY_EXISTS);
		}

		ModerationAppeal appeal = moderationAppealRepository.save(request.toAppeal(action, member));
		return ModerationAppealCreateResponse.from(appeal);
	}

	private void validateAppealableAction(ModerationAction action, Member member) {
		if (action.getMember() == null || !action.getMember().getId().equals(member.getId())) {
			throw new AbuseReportException(AbuseReportErrorCode.APPEAL_FORBIDDEN);
		}
		if (!APPEALABLE_ACTIONS.contains(action.getAction())) {
			throw new AbuseReportException(AbuseReportErrorCode.APPEAL_ACTION_NOT_ALLOWED);
		}
		if (isAppealPeriodExpired(action.getCreatedAt())) {
			throw new AbuseReportException(AbuseReportErrorCode.APPEAL_PERIOD_EXPIRED);
		}
	}

	private boolean isAppealPeriodExpired(LocalDateTime actionCreatedAt) {
		return actionCreatedAt != null
			&& LocalDateTime.now().isAfter(actionCreatedAt.plusDays(APPEAL_PERIOD_DAYS));
	}
}
