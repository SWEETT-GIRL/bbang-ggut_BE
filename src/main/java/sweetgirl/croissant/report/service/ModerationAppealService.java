package sweetgirl.croissant.report.service;

import java.time.LocalDateTime;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.report.code.UserReportErrorCode;
import sweetgirl.croissant.report.dto.request.ModerationAppealCreateRequest;
import sweetgirl.croissant.report.dto.response.ModerationAppealCreateResponse;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.ModerationAppeal;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.exception.UserReportException;
import sweetgirl.croissant.report.repository.ModerationActionRepository;
import sweetgirl.croissant.report.repository.ModerationAppealRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ModerationAppealService {

	private static final long APPEAL_PERIOD_DAYS = 30L;
	private static final Set<UserReportAction> APPEALABLE_ACTIONS = Set.of(
		UserReportAction.HIDE_REVIEW,
		UserReportAction.DELETE_REVIEW,
		UserReportAction.RESET_PROFILE,
		UserReportAction.RESTRICT_MEMBER,
		UserReportAction.SUSPEND_MEMBER);

	private final MemberReader memberReader;
	private final ModerationActionRepository moderationActionRepository;
	private final ModerationAppealRepository moderationAppealRepository;

	@Transactional
	public ModerationAppealCreateResponse createAppeal(
			Long memberId,
			ModerationAppealCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		ModerationAction action = moderationActionRepository.findByIdWithMemberAndReport(request.actionId())
			.orElseThrow(() -> new UserReportException(UserReportErrorCode.APPEAL_ACTION_NOT_FOUND));

		validateAppealableAction(action, member);
		if (moderationAppealRepository.existsByModerationActionIdAndMemberId(action.getId(), member.getId())) {
			throw new UserReportException(UserReportErrorCode.APPEAL_ALREADY_EXISTS);
		}

		ModerationAppeal appeal = moderationAppealRepository.save(request.toAppeal(action, member));
		return ModerationAppealCreateResponse.from(appeal);
	}

	private void validateAppealableAction(ModerationAction action, Member member) {
		if (action.getMember() == null || !action.getMember().getId().equals(member.getId())) {
			throw new UserReportException(UserReportErrorCode.APPEAL_FORBIDDEN);
		}
		if (!APPEALABLE_ACTIONS.contains(action.getAction())) {
			throw new UserReportException(UserReportErrorCode.APPEAL_ACTION_NOT_ALLOWED);
		}
		if (isAppealPeriodExpired(action.getCreatedAt())) {
			throw new UserReportException(UserReportErrorCode.APPEAL_PERIOD_EXPIRED);
		}
	}

	private boolean isAppealPeriodExpired(LocalDateTime actionCreatedAt) {
		return actionCreatedAt != null
			&& LocalDateTime.now().isAfter(actionCreatedAt.plusDays(APPEAL_PERIOD_DAYS));
	}
}
