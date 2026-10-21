package sweetgirl.croissant.monthlyreport.service;

import java.time.Instant;
import java.time.YearMonth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportJobRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

/** 정책·세계관 조립기가 준비한 초안을 등록한다. 재등록으로 기존 작업이나 발행본을 덮어쓰지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportJobService {
	private final MonthlyReportJobRepository jobs;
	private final MonthlyReportRepository reports;
	private final MemberRepository members;
	private final MonthlyReportPayloadCodec codec;

	public boolean enqueue(MonthlyReportResponse draft, ObjectNode metrics, String version) {
		if (draft == null || metrics == null || metrics.isEmpty() || version == null
			|| version.isBlank() || version.length() > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		YearMonth month = MonthlyReportMonth.parse(draft.yearMonth());
		if (!month.isBefore(YearMonth.now(UserInteractionIngestCommandService.ZONE_ID))) {
			throw new CustomException(MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		}
		String payload = codec.write(draft, draft.memberId(), draft.yearMonth());
		codec.readPhotoSources(metrics.toString());
		if (!members.existsByIdAndMemberTypeAndDeletedAtIsNullAndCreatedAtBefore(draft.memberId(), "USER",
			month.plusMonths(1).atDay(1).atStartOfDay())) {
			return false;
		}
		if (reports.findById(new MonthlyReportMemberId(draft.memberId(), draft.yearMonth()))
			.filter(report -> report.getStatus() == MonthlyReportStatus.PUBLISHED).isPresent()) {
			return false;
		}
		return jobs.enqueue(draft.memberId(), draft.yearMonth(), payload, metrics.toString(), version,
			Instant.now()) == 1;
	}
}
