package sweetgirl.croissant.monthlyreport.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportAggregationBasis;

/** 한 번의 월말 원천 조회 결과를 모든 집계가 공유한다. 파일 키와 개인 메모는 담지 않는다. */
public record MonthlyReportSourceSnapshot(
		LocalDateTime coveredFrom, MonthlyReportAggregationBasis aggregationBasis, LocalDateTime sourceAsOf,
		List<RecordState> records, List<TagState> tags,
		Map<Long, PhotoState> photos, Set<Long> savedStoreIds, Set<Long> plannedStoreIds, long courses) {

	public MonthlyReportSourceSnapshot {
		records = List.copyOf(records);
		tags = List.copyOf(tags);
		photos = Map.copyOf(photos);
		savedStoreIds = Set.copyOf(savedStoreIds);
		plannedStoreIds = Set.copyOf(plannedStoreIds);
	}

	public record RecordState(Long id, Long storeId, String regionKey, LocalDateTime createdAt,
			LocalDateTime eatenAt, LocalDateTime deletedAt, String lovedBread, List<String> lovedBreads,
			String recordType) {
		public RecordState(Long id, Long storeId, String regionKey, LocalDateTime createdAt,
				LocalDateTime eatenAt, LocalDateTime deletedAt, String lovedBread, List<String> lovedBreads) {
			this(id, storeId, regionKey, createdAt, eatenAt, deletedAt, lovedBread, lovedBreads, "NORMAL");
		}

		public RecordState {
			lovedBreads = List.copyOf(lovedBreads);
		}

		public boolean loves(String tagName) {
			return lovedBreads.contains(tagName) || tagName.equals(lovedBread);
		}
	}

	public record TagState(Long id, Long recordId, Long photoId, String name, String breadCategory, MenuKind menuKind) {
		public String category() {
			return BreadRecordTag.resolveCategory(breadCategory, menuKind);
		}
	}

	public record PhotoState(Long id, Long recordId, UUID referenceId, int sortOrder) {
	}

	public record RelationState(Long storeId, LocalDateTime createdAt, LocalDateTime deletedAt) {
	}

	public record CourseState(Long id, LocalDateTime createdAt, LocalDateTime deletedAt) {
	}
}
