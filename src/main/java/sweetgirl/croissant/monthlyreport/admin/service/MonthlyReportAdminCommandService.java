package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportRunResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportDraftRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportMonth;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTargetReader;

@Service
@RequiredArgsConstructor
@Transactional
public class MonthlyReportAdminCommandService {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportDraftRepository drafts;
	private final MonthlyReportTargetReader targets;

	@Transactional
	public MonthlyReportRunResponse createReportRun(String month, String version) {
		return createReportRun(month, version, null);
	}

	@Transactional
	public MonthlyReportRunResponse createReportRun(String month, String version, Long targetMemberId) {
		if (!MonthlyReportMonth.parse(month).isBefore(YearMonth.now(ZoneId.of("Asia/Seoul")))) {
			throw new CustomException(MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		}
		if (targetMemberId != null && !targets.isEligible(targetMemberId, month)) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
		runs.enqueue(UUID.randomUUID(), month, version, Instant.now(), targetMemberId);
		MonthlyReportRun run = runs.findByYearMonthAndTargetMemberId(month, targetMemberId).orElseThrow();
		if (!run.getAssetVersion().equals(version)) {
			throw createRunConflictException();
		}
		return MonthlyReportRunResponse.from(run);
	}

	@Transactional
	public void registerAutomaticReportRun(String month, String version) {
		if (!MonthlyReportMonth.parse(month).isBefore(YearMonth.now(ZoneId.of("Asia/Seoul")))) {
			throw new CustomException(MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		}
		runs.enqueue(UUID.randomUUID(), month, version, Instant.now(), null);
	}

	@Transactional
	public MonthlyReportRunResponse approveReportPublication(String month, UUID id, Instant pushAt) {
		MonthlyReportRun run = lockReportRun(month, id);
		if (run.getState() == MonthlyReportRun.State.PUBLISHING || run.getState() == MonthlyReportRun.State.COMPLETE) {
			if (!Objects.equals(run.getPushAt(), pushAt)) {
				throw createRunConflictException();
			}
			return MonthlyReportRunResponse.from(run);
		}
		if (run.getState() != MonthlyReportRun.State.REVIEW || !run.isScanComplete()
			|| drafts.countByRunIdAndState(id, MonthlyReportDraft.State.FAILED) > 0) {
			throw createRunConflictException();
		}
		if (pushAt != null && pushAt.isBefore(Instant.now())) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
		run.approve(pushAt);
		return MonthlyReportRunResponse.from(run);
	}

	@Transactional
	public MonthlyReportRunResponse retryReportRun(String month, UUID id) {
		MonthlyReportRun run = lockReportRun(month, id);
		if (run.getState() != MonthlyReportRun.State.REVIEW && run.getState() != MonthlyReportRun.State.BLOCKED) {
			throw createRunConflictException();
		}
		boolean publishing = drafts.countByRunIdAndState(id, MonthlyReportDraft.State.PUBLISH_FAILED) > 0;
		drafts.retryFailedDrafts(id);

		run.retry(publishing);
		return MonthlyReportRunResponse.from(run);
	}

	private MonthlyReportRun lockReportRun(String month, UUID id) {
		MonthlyReportMonth.parse(month);
		return runs.lock(id).filter(run -> run.getYearMonth().equals(month))
			.orElseThrow(this::createRunConflictException);
	}

	private CustomException createRunConflictException() {
		return new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
	}
}
