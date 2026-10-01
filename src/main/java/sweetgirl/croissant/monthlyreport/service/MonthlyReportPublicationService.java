package sweetgirl.croissant.monthlyreport.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

/** 집계 배치 연결점. 대상·이력 완전성·산식 정책이 확정된 집계 결과만 호출자가 넘겨야 한다. */
@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportPublicationService {

	private final MemberReader memberReader;
	private final MonthlyReportRepository repository;
	private final MonthlyReportPayloadCodec codec;
	private final MonthlyReportNotificationService notificationService;

	public boolean publish(Long memberId, String yearMonth, MonthlyReportResponse report, JsonNode rawMetrics,
			String calculationVersion, Instant generatedAt) {
		Member member = memberReader.getMember(memberId);
		YearMonth month = MonthlyReportMonth.parse(yearMonth);
		Instant publishedAt = Instant.now();
		Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
		if (generatedAt == null || generatedAt.isBefore(monthEnd) || generatedAt.isAfter(publishedAt)
			|| rawMetrics == null || !rawMetrics.isObject() || rawMetrics.isEmpty()
			|| calculationVersion == null || calculationVersion.isBlank() || calculationVersion.length() > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		// 순회 이후 탈퇴·유형 변경도 다시 확인한다. 활동이 없어도 일반 회원이면 발행한다.
		if (member.isDeleted() || !"USER".equals(member.getMemberType())
			|| member.getCreatedAt() == null
			|| !member.getCreatedAt().isBefore(month.plusMonths(1).atDay(1).atStartOfDay())) {
			return false;
		}
		String payload = codec.write(report, memberId, yearMonth);
		codec.readPhotoSources(rawMetrics.toString());
		boolean published = repository.publish(memberId, yearMonth, payload, rawMetrics.toString(), calculationVersion,
			generatedAt, publishedAt) == 1;
		if (published) {
			notificationService.recordPublished(member, month,
				LocalDateTime.ofInstant(publishedAt, ZoneId.of("Asia/Seoul")));
		}
		return published;
	}
}
