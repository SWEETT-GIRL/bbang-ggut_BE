package sweetgirl.croissant.monthlyreport.service;

import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.personalization.repository.UserInteractionIngestDayRepository;
import sweetgirl.croissant.personalization.repository.UserStoreInteractionRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
public class MonthlyReportViewedStoreAggregationService {
	public static final String VERSION = "distinct-viewed-stores-v1";

	private final UserInteractionIngestDayRepository ingestDays;
	private final UserStoreInteractionRepository interactions;

	public long aggregate(Long memberId, String yearMonth) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		if (ingestDays.countByIngestDateGreaterThanEqualAndIngestDateLessThan(
			month.atDay(1), month.plusMonths(1).atDay(1)) != month.lengthOfMonth()) {
			throw new CustomException(MonthlyReportErrorCode.VIEW_INGEST_INCOMPLETE);
		}
		return interactions.countMonthlyViewedStores(memberId, month.atDay(1).atStartOfDay(),
			month.plusMonths(1).atDay(1).atStartOfDay());
	}
}
