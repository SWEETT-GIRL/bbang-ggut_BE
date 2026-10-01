package sweetgirl.croissant.monthlyreport.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;

/** 태그 한 개를 기록 한 회로 센다. 실제 섭취 수량을 추정하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportBreadAggregationService {

	public static final String VERSION = "bread-tags-v1";

	private final BreadRecordTagRepository tagRepository;

	public Result aggregate(Long memberId, String yearMonth) {
		var month = MonthlyReportMonth.parse(yearMonth);
		var tags = tagRepository.findMonthlyReportTags(memberId,
			month.atDay(1).atStartOfDay(), month.plusMonths(1).atDay(1).atStartOfDay());
		Map<String, Long> counts = new HashMap<>();
		Map<String, String> images = new HashMap<>();
		long uncategorized = 0;
		for (var tag : tags) {
			if (tag.uncategorized()) {
				uncategorized++;
				continue;
			}
			String category = tag.resolvedCategory();
			counts.merge(category, 1L, Long::sum);
			// 최고였던 빵 우선, 같은 우선순위에서는 최신 섭취 기록·사진 순서대로 고정한다.
			images.putIfAbsent(category, imageKey(tag.getPhoto()));
		}
		List<MonthlyReportResponse.Bread> breads = counts.entrySet().stream()
			.map(entry -> new MonthlyReportResponse.Bread(entry.getKey(), entry.getValue(), images.get(entry.getKey())))
			.sorted(Comparator.comparing(MonthlyReportResponse.Bread::count).reversed()
				.thenComparing(MonthlyReportResponse.Bread::name))
			.toList();
		return new Result(tags.size(), uncategorized, breads);
	}

	private String imageKey(BreadRecordPhoto photo) {
		return Stream
			.of(photo.getResizedKey640(), photo.getResizedKey320(), photo.getResizedKey1024(), photo.getAwsKey())
			.filter(key -> key != null && !key.isBlank()).findFirst().orElse(null);
	}

	public record Result(long total, long uncategorized, List<MonthlyReportResponse.Bread> breads) {

		public Result {
			breads = List.copyOf(breads);
		}

		public MonthlyReportResponse applyTo(MonthlyReportResponse draft) {
			return new MonthlyReportResponse(draft.schemaVersion(), draft.yearMonth(), draft.memberId(),
				draft.nickname(), draft.character(), draft.planet(),
				new MonthlyReportResponse.Summary(draft.summary().records(), total, draft.summary().stores()),
				draft.stores(), breads, draft.activity(), draft.comparison(), draft.towns());
		}
	}
}
