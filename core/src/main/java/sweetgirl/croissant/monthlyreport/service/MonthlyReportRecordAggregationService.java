package sweetgirl.croissant.monthlyreport.service;

import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RecordState;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportRecordAggregationService {

	public static final String VERSION = "records-history-v3";

	private final MonthlyReportSourceReader sourceReader;
	private final MonthlyReportRepository reportRepository;
	private final MonthlyReportPayloadCodec codec;

	public Result aggregate(Long memberId, String yearMonth) {
		return aggregate(memberId, yearMonth, sourceReader.read(memberId, yearMonth));
	}

	public Result aggregate(Long memberId, String yearMonth, MonthlyReportSourceSnapshot snapshot) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		long stores = snapshot.records().stream().map(RecordState::storeId).filter(id -> id != null).distinct().count()
			+ snapshot.records().stream().filter(record -> record.storeId() == null).count();
		String previousMonth = month.minusMonths(1).toString();
		Long previousRecords = reportRepository.findById(new MonthlyReportMemberId(memberId, previousMonth))
			.filter(report -> report.getStatus() == MonthlyReportStatus.PUBLISHED)
			.map(report -> codec.read(report.getPayload(), memberId, previousMonth).summary().records())
			.orElse(null);
		if (previousRecords != null) {
			return new Result(snapshot.records().size(), stores, previousRecords, "published-report");
		}
		if (month.minusMonths(1).getYear() >= 2000) {
			try {
				MonthlyReportSourceReader.RecordCount previous = sourceReader.countRecords(memberId, previousMonth);
				return new Result(snapshot.records().size(), stores, previous.records(), previous.basis().name());
			} catch (CustomException exception) {
				if (exception.getErrorCode() != MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE) {
					throw exception;
				}
			}
		}
		return new Result(snapshot.records().size(), stores, null, "unavailable");
	}

	public record Result(long records, long stores, Long previousRecords, String previousSource) {

		public MonthlyReportResponse applyTo(MonthlyReportResponse draft) {
			return new MonthlyReportResponse(draft.schemaVersion(), draft.yearMonth(), draft.memberId(),
				draft.nickname(), draft.character(), draft.planet(),
				new MonthlyReportResponse.Summary(records, draft.summary().breads(), stores), draft.stores(),
				draft.breads(), draft.activity(), new MonthlyReportResponse.Comparison(previousRecords, records),
				draft.towns());
		}
	}
}
