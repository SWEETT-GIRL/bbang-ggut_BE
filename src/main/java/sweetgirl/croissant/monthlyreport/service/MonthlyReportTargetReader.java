package sweetgirl.croissant.monthlyreport.service;

import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;

/** 배치가 마지막으로 처리한 회원 ID 다음부터 읽는다. 활동 유무로 회원을 제외하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportTargetReader {
	private final MemberRepository members;

	public Slice<Long> nextPage(String yearMonth, long afterMemberId, int size) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		if (!month.isBefore(YearMonth.now(ZoneId.of("Asia/Seoul")))) {
			throw new CustomException(MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		}
		if (afterMemberId < 0 || size < 1 || size > 1000) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_BATCH_PAGE);
		}
		return members.findMonthlyReportTargets(yearMonth, month.plusMonths(1).atDay(1).atStartOfDay(),
			afterMemberId, PageRequest.of(0, size));
	}
}
