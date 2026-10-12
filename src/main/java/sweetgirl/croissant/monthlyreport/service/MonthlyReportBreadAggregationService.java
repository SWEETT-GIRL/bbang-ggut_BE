package sweetgirl.croissant.monthlyreport.service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportPhotoSource;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.PhotoState;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.RecordState;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot.TagState;

/** 월말 이력의 태그 한 개를 기록 한 회로 센다. 실제 섭취 수량을 추정하지 않는다. */
@Service
@RequiredArgsConstructor
public class MonthlyReportBreadAggregationService {

	public static final String VERSION = "bread-history-v3";
	private final MonthlyReportSourceReader sourceReader;

	public Result aggregate(Long memberId, String yearMonth) {
		return aggregate(sourceReader.read(memberId, yearMonth));
	}

	public Result aggregate(MonthlyReportSourceSnapshot snapshot) {
		Map<Long, RecordState> records = snapshot.records().stream()
			.collect(Collectors.toMap(RecordState::id, record -> record));
		Map<Long, PhotoState> photos = snapshot.photos();
		Comparator<TagState> priority = Comparator
			.comparingInt((TagState tag) -> records.get(tag.recordId()).loves(tag.name()) ? 0 : 1)
			.thenComparing(tag -> records.get(tag.recordId()).eatenAt(), Comparator.reverseOrder())
			.thenComparing(TagState::recordId, Comparator.reverseOrder())
			.thenComparingInt(tag -> photos.get(tag.photoId()).sortOrder())
			.thenComparing(TagState::photoId).thenComparing(TagState::id);
		List<TagState> tags = snapshot.tags().stream()
			// 사진에 안 붙은 빵 이름(photoId 없음)은 건너뛴다 — 불변 맵은 null 키를 물으면 던진다
			.filter(tag -> tag.photoId() != null
				&& records.containsKey(tag.recordId()) && photos.containsKey(tag.photoId())
				&& tag.recordId().equals(photos.get(tag.photoId()).recordId()))
			.sorted(priority).toList();
		Map<String, Long> counts = new HashMap<>();
		Map<String, MonthlyReportPhotoSource> sources = new HashMap<>();
		long uncategorized = 0;
		for (TagState tag : tags) {
			String category = tag.category();
			if (MenuCategory.UNCATEGORIZED_KINDS.stream().anyMatch(kind -> kind.name().equals(category))) {
				uncategorized++;
				continue;
			}
			counts.merge(category, 1L, Long::sum);
			sources.putIfAbsent(category, new MonthlyReportPhotoSource(tag.recordId(),
				photos.get(tag.photoId()).referenceId()));
		}
		// 파일 키는 이력에 복제하지 않는다. 조회 서비스가 선택된 참조의 현재 사진·태그를 확인한다.
		List<MonthlyReportResponse.Bread> breads = counts.entrySet().stream()
			.map(entry -> new MonthlyReportResponse.Bread(entry.getKey(), entry.getValue(), null))
			.sorted(Comparator.comparing(MonthlyReportResponse.Bread::count).reversed()
				.thenComparing(MonthlyReportResponse.Bread::name))
			.toList();
		return new Result(tags.size(), uncategorized, breads, sources);
	}

	public record Result(long total, long uncategorized, List<MonthlyReportResponse.Bread> breads,
			Map<String, MonthlyReportPhotoSource> photoSources) {

		public Result {
			breads = List.copyOf(breads);
			photoSources = Map.copyOf(photoSources);
		}

		public MonthlyReportResponse applyTo(MonthlyReportResponse draft) {
			return new MonthlyReportResponse(draft.schemaVersion(), draft.yearMonth(), draft.memberId(),
				draft.nickname(), draft.character(), draft.planet(),
				new MonthlyReportResponse.Summary(draft.summary().records(), total, draft.summary().stores()),
				draft.stores(), breads, draft.activity(), draft.comparison(), draft.towns());
		}
	}
}
