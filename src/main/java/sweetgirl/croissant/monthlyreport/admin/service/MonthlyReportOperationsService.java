package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.SliceResponse;
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
@Transactional(readOnly = true)
public class MonthlyReportOperationsService {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportDraftRepository drafts;
	private final MonthlyReportNotificationRepository reportNotifications;
	private final MonthlyReportPushRepository pushes;

	public Operations getReportOperations(String month, UUID runId, int page, int size) {
		validateReportRunExists(month, runId);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		Map<String, Long> states = new LinkedHashMap<>();
		long published = 0;
		long viewed = 0;
		long notificationOpened = 0;
		for (MonthlyReportNotificationRepository.Summary group : reportNotifications.summarize(runId)) {
			states.put(group.getPushState(), group.getMembers());
			published += group.getPublished();
			viewed += group.getViewed();
			notificationOpened += group.getNotificationOpened();
		}
		return new Operations(published, viewed, notificationOpened, states,
			SliceResponse.from(reportNotifications.status(runId, PageRequest.of(page, size))));
	}

	/** 자동 재시도를 모두 소진한 실패 건만 새 시도 묶음을 예약한다. 성공·제외·예약 대기는 재발송하지 않는다. */
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

	public record Operations(long published, long viewed, long notificationOpened, Map<String, Long> pushCounts,
			SliceResponse<MonthlyReportNotificationRepository.MemberStatus> members) {
	}
}
