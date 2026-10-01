package sweetgirl.croissant.monthlyreport.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

/** 나머지 통계가 준비된 초안에 실제 빵 집계를 채워 발행한다. 대상 회원 순회와 나머지 집계는 배치 책임이다. */
@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportGenerationService {

	private final MonthlyReportRepository repository;
	private final MonthlyReportPayloadCodec codec;
	private final MonthlyReportBreadAggregationService breadAggregationService;
	private final MonthlyReportPublicationService publicationService;

	public boolean publishFromDraft(MonthlyReportResponse draft, ObjectNode rawMetrics, String calculationVersion) {
		if (draft == null || rawMetrics == null || rawMetrics.isEmpty()) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		codec.write(draft, draft.memberId(), draft.yearMonth());
		var existing = repository.findById(new MonthlyReportMemberId(draft.memberId(), draft.yearMonth()));
		if (existing.isPresent() && existing.get().getStatus() == MonthlyReportStatus.PUBLISHED) {
			return false;
		}
		var breads = breadAggregationService.aggregate(draft.memberId(), draft.yearMonth());
		ObjectNode metrics = rawMetrics.deepCopy();
		metrics.putObject("breadAggregation")
			.put("version", MonthlyReportBreadAggregationService.VERSION)
			.put("photoSelection", "loved-then-latest-eaten")
			.put("totalTags", breads.total()).put("uncategorizedTags", breads.uncategorized());
		return publicationService.publish(draft.memberId(), draft.yearMonth(), breads.applyTo(draft), metrics,
			calculationVersion, Instant.now());
	}
}
