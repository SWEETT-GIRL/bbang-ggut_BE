package sweetgirl.croissant.monthlyreport.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportSourceSnapshot;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

/** 초안에 월말 이력의 기록·빵·저장·계획·코스와 전월 비교를 채워 발행한다. 대상 회원 순회와 나머지 집계는 배치 책임이다. */
@Service
@RequiredArgsConstructor
public class MonthlyReportGenerationService {

	private final MonthlyReportRepository repository;
	private final MonthlyReportPayloadCodec codec;
	private final MonthlyReportBreadAggregationService breadAggregationService;
	private final MonthlyReportRecordAggregationService recordAggregationService;
	private final MonthlyReportPublicationService publicationService;
	private final MonthlyReportSourceReader sourceReader;
	private final MonthlyReportViewedStoreAggregationService viewedStoreAggregation;
	private final MonthlyReportTownAggregationService townAggregation;
	private final MonthlyReportActivityPolicy activityPolicy;

	public boolean publishFromDraft(MonthlyReportResponse draft, ObjectNode rawMetrics, String calculationVersion) {
		if (draft == null || rawMetrics == null || rawMetrics.isEmpty()) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		codec.write(draft, draft.memberId(), draft.yearMonth());
		Optional<MonthlyReportMember> existing = repository.findById(
			new MonthlyReportMemberId(draft.memberId(), draft.yearMonth()));
		if (existing.isPresent() && existing.get().getStatus() == MonthlyReportStatus.PUBLISHED) {
			return false;
		}
		Prepared prepared = prepare(draft, rawMetrics);
		return publicationService.publish(draft.memberId(), draft.yearMonth(), prepared.report(),
			prepared.metrics(), calculationVersion, prepared.generatedAt());
	}

	public Prepared prepare(MonthlyReportResponse draft, ObjectNode rawMetrics) {
		codec.write(draft, draft.memberId(), draft.yearMonth());
		MonthlyReportSourceSnapshot source = sourceReader.read(draft.memberId(), draft.yearMonth());
		MonthlyReportRecordAggregationService.Result records = recordAggregationService.aggregate(
			draft.memberId(), draft.yearMonth(), source);
		MonthlyReportBreadAggregationService.Result breads = breadAggregationService.aggregate(source);
		MonthlyReportResponse.Stores stores = new MonthlyReportResponse.Stores(
			viewedStoreAggregation.aggregate(draft.memberId(), draft.yearMonth()),
			(long)source.savedStoreIds().size(), (long)source.plannedStoreIds().size(), source.courses());
		List<MonthlyReportResponse.Town> towns = townAggregation.aggregate(source);
		ObjectNode metrics = rawMetrics.deepCopy();
		metrics.putObject("viewedStoreAggregation").put("version", MonthlyReportViewedStoreAggregationService.VERSION)
			.put("viewed", stores.viewed()).put("source", "user_store_interaction.STORE_VIEW");
		metrics.putObject("townAggregation").put("version", MonthlyReportTownAggregationService.VERSION)
			.put("towns", towns.size()).put("source", "bread_record.region_key history");
		metrics.putObject("sourceHistory").put("version", "source-history-v1")
			.put("coveredFrom", source.coveredFrom().toString());
		metrics.putObject("storeAggregation").put("version", "retained-relations-v1")
			.put("saved", stores.saved()).put("planned", stores.planned()).put("courses", stores.courses());
		metrics.putObject("recordAggregation")
			.put("version", MonthlyReportRecordAggregationService.VERSION)
			.put("records", records.records()).put("stores", records.stores())
			.put("previousRecords", records.previousRecords())
			.put("previousSource", "published-report");
		metrics.putObject("breadAggregation")
			.put("version", MonthlyReportBreadAggregationService.VERSION)
			.put("photoSelection", "loved-then-latest-eaten")
			.put("totalTags", breads.total()).put("uncategorizedTags", breads.uncategorized());
		ObjectNode sources = metrics.putObject("breadPhotoSources");
		breads.photoSources().forEach((name, photoSource) -> sources.putObject(name)
			.put("recordId", photoSource.recordId())
			.put("photoReferenceId", photoSource.photoReferenceId().toString()));
		MonthlyReportResponse report = activityPolicy.applyTo(
			breads.applyTo(records.applyTo(draft)).withStores(stores).withTowns(towns), metrics);
		return new Prepared(report, metrics, Instant.now());
	}

	public record Prepared(MonthlyReportResponse report, ObjectNode metrics, Instant generatedAt) {
	}
}
