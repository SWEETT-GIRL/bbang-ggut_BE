package sweetgirl.croissant.monthlyreport.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestDayRepository;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

/** 날짜별로 독립 커밋한다. 중간 실패 후 재시도하면 완료된 날짜는 건너뛴다. 추천 재계산은 호출하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class MonthlyReportIngestRecoveryService {
	private final UserInteractionIngestDayRepository days;
	private final UserInteractionIngestCommandService ingest;

	public int ensureComplete(String yearMonth) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		if (!month.isBefore(YearMonth.now(UserInteractionIngestCommandService.ZONE_ID))) {
			throw new CustomException(MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		}
		LocalDate from = month.atDay(1);
		LocalDate until = month.plusMonths(1).atDay(1);
		Set<LocalDate> completed = new HashSet<>(days.findCompletedDates(from, until));
		int recovered = 0;
		for (LocalDate day = from; day.isBefore(until); day = day.plusDays(1)) {
			if (!completed.contains(day) && ingest.ingestMissingDay(day)) {
				recovered++;
			}
		}
		if (days.countByIngestDateGreaterThanEqualAndIngestDateLessThan(from, until) != month.lengthOfMonth()) {
			throw new CustomException(MonthlyReportErrorCode.VIEW_INGEST_INCOMPLETE);
		}
		return recovered;
	}
}
