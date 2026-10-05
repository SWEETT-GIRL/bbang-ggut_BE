package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraftId;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportDraftRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportMonth;

@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportOperationsCommandService {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportDraftRepository drafts;
	private final MonthlyReportNotificationRepository reportNotifications;
	private final MonthlyReportPushRepository pushes;

	@Transactional
	public void retryFailedReportPush(String month, UUID runId, Long memberId) {
		validateReportRunExists(month, runId);
		if (!drafts.existsById(new MonthlyReportDraftId(runId, memberId, month))) {
			throw new CustomException(MonthlyReportErrorCode.ADMIN_NOT_FOUND);
		}
		Long notificationId = reportNotifications.findById(new MonthlyReportMemberId(memberId, month))
			.map(value -> value.getNotificationId()).orElseThrow(this::createRunConflictException);
		MonthlyReportPush push = pushes.lock(notificationId).orElseThrow(this::createRunConflictException);
		if (!push.getMemberId().equals(memberId) || reportNotifications.hasSuccessfulPush(notificationId, memberId)
			|| !push.retry()) {
			throw createRunConflictException();
		}
	}

	private void validateReportRunExists(String month, UUID id) {
		MonthlyReportMonth.parse(month);
		runs.findById(id).filter(run -> run.getYearMonth().equals(month))
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ADMIN_NOT_FOUND));
	}

	private CustomException createRunConflictException() {
		return new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
	}
}
