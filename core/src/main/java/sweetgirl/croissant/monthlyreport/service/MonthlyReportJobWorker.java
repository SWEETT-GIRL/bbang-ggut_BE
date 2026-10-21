package sweetgirl.croissant.monthlyreport.service;

import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.monthlyreport.config.MonthlyReportBatchProperties;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportJob;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportJobRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyReportJobWorker {
	private final MonthlyReportJobRepository jobs;
	private final MonthlyReportRepository reports;
	private final MemberRepository members;
	private final MonthlyReportJobPublisher publisher;
	private final MonthlyReportBatchProperties properties;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Outcome run(Long memberId, String yearMonth) {
		Optional<MonthlyReportJob> locked = jobs.lockJob(memberId, yearMonth);
		if (locked.isEmpty()) {
			return Outcome.SKIPPED;
		}
		MonthlyReportJob job = locked.get();
		Instant now = Instant.now();
		if (!job.canRun(now, properties.getMaxAttempts())) {
			return Outcome.SKIPPED;
		}
		if (published(memberId, yearMonth)) {
			job.complete();
			return Outcome.SKIPPED;
		}
		if (!members.existsByIdAndMemberTypeAndDeletedAtIsNullAndCreatedAtBefore(memberId, "USER",
			YearMonth.parse(yearMonth).plusMonths(1).atDay(1).atStartOfDay())) {
			job.cancel();
			return Outcome.SKIPPED;
		}
		job.begin(now);
		try {
			boolean created = publisher.publish(job);
			if (created || published(memberId, yearMonth)) {
				job.complete();
			} else {
				job.cancel();
			}
			return created ? Outcome.PUBLISHED : Outcome.SKIPPED;
		} catch (RuntimeException e) {
			// 예외 메시지에는 원본 내용이 포함될 수 있어 코드만 저장한다.
			String error = e instanceof CustomException custom ? custom.getErrorCode().getCode() : "GENERATION_FAILED";
			job.fail(error, Instant.now().plus(properties.retryDelay(job.getAttempts())));
			if (reports.recordGenerationFailure(memberId, yearMonth) == 0) {
				// 발행 커밋 뒤 후속 처리만 실패한 경우에도 이미 발행된 결과를 우선한다.
				job.complete();
				return Outcome.SKIPPED;
			}
			log.warn("[월간 보고서] 회원 처리 실패 memberId={} month={} attempt={} code={}",
				memberId, yearMonth, job.getAttempts(), error);
			return Outcome.FAILED;
		}
	}

	private boolean published(Long memberId, String yearMonth) {
		return reports.findById(new MonthlyReportMemberId(memberId, yearMonth))
			.filter(report -> report.getStatus() == MonthlyReportStatus.PUBLISHED).isPresent();
	}

	public enum Outcome {
		PUBLISHED, FAILED, SKIPPED
	}
}
