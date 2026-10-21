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
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportMemberStatusResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportOperationsResponse;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportMonth;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportOperationsQueryService {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportNotificationRepository reportNotifications;

	public MonthlyReportOperationsResponse getReportOperations(String month, UUID runId, int page, int size) {
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
		return new MonthlyReportOperationsResponse(published, viewed, notificationOpened, states,
			SliceResponse.from(reportNotifications.status(runId, PageRequest.of(page, size))
				.map(MonthlyReportMemberStatusResponse::from)));
	}

	private void validateReportRunExists(String month, UUID id) {
		MonthlyReportMonth.parse(month);
		runs.findById(id).filter(run -> run.getYearMonth().equals(month))
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ADMIN_NOT_FOUND));
	}
}
