package sweetgirl.croissant.monthlyreport.service;

import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository.MonthlyRecordCounts;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportRecordAggregationService {

	public static final String VERSION = "records-month-end-v1";

	private final BreadRecordRepository recordRepository;
	private final MonthlyReportRepository reportRepository;
	private final MonthlyReportPayloadCodec codec;

	public Result aggregate(Long memberId, String yearMonth) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		MonthlyRecordCounts counts = recordRepository.countMonthlyReportRecords(memberId,
			month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
		String previousMonth = month.minusMonths(1).toString();
		Long previousRecords = reportRepository.findById(new MonthlyReportMemberId(memberId, previousMonth))
			.filter(report -> report.getStatus() == MonthlyReportStatus.PUBLISHED)
			.map(report -> codec.read(report.getPayload(), memberId, previousMonth).summary().records())
			.orElse(null);
		return new Result(counts.getRecords(), counts.getStores(), previousRecords);
	}

	public record Result(long records, long stores, Long previousRecords) {

		public MonthlyReportResponse applyTo(MonthlyReportResponse draft) {
			return new MonthlyReportResponse(draft.schemaVersion(), draft.yearMonth(), draft.memberId(),
				draft.nickname(), draft.character(), draft.planet(),
				new MonthlyReportResponse.Summary(records, draft.summary().breads(), stores), draft.stores(),
				draft.breads(), draft.activity(), new MonthlyReportResponse.Comparison(previousRecords, records),
				draft.towns());
		}
	}
}
