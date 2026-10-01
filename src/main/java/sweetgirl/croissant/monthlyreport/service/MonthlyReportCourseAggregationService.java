package sweetgirl.croissant.monthlyreport.service;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;

/** 공동 편집 여부와 관계없이 코스 소유자에게 월간 통계를 귀속한다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportCourseAggregationService {

	private final CourseRepository courseRepository;
	private final CourseStoreRepository courseStoreRepository;

	public Result aggregate(Long memberId, String yearMonth) {
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		LocalDateTime from = month.atDay(1).atStartOfDay();
		LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
		return new Result(courseRepository.countMonthlyReportCourses(memberId, from, to),
			courseStoreRepository.findMonthlyReportPlannedStoreIds(memberId, from, to));
	}

	public record Result(long courses, Set<Long> plannedStoreIds) {

		public Result {
			plannedStoreIds = Set.copyOf(plannedStoreIds);
		}

		/** 직접 계획에 담은 가게와 코스 가게를 합칠 때도 같은 가게는 한 번만 센다. */
		public long countPlannedStoresWith(Set<Long> directPlanStoreIds) {
			Set<Long> allStoreIds = new HashSet<>(plannedStoreIds);
			allStoreIds.addAll(directPlanStoreIds);
			return allStoreIds.size();
		}
	}
}
