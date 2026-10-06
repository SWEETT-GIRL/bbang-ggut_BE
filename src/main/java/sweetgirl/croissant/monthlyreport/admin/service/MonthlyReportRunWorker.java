package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportDraftRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportSourceHistoryRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportFailureLogger;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportGenerationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportIngestRecoveryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTargetReader;

@Service
@RequiredArgsConstructor
public class MonthlyReportRunWorker {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportDraftRepository drafts;
	private final MonthlyReportRepository reports;
	private final MonthlyReportSourceHistoryRepository history;
	private final MonthlyReportIngestRecoveryService recovery;
	private final MonthlyReportTargetReader targets;
	private final MonthlyReportPreparation preparation;
	private final MonthlyReportAdminPublisher publisher;
	private final MonthlyReportPayloadCodec codec;

	/** 한 회원씩 커밋한다. 프로세스 중단 후에도 DB 상태에서 이어간다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processReportRunStep(UUID runId) {
		Optional<MonthlyReportRun> locked = runs.lock(runId);
		if (locked.isEmpty()) {
			return;
		}
		MonthlyReportRun run = locked.get();
		if (run.getState() == MonthlyReportRun.State.PREPARING) {
			prepare(run);
		} else if (run.getState() == MonthlyReportRun.State.PUBLISHING) {
			publish(run);
		}
	}

	private void prepare(MonthlyReportRun run) {
		if (!run.isSourcesReady()) {
			try {
				LocalDateTime coverage = history.findCoveredFrom();
				if (coverage == null) {
					throw new CustomException(MonthlyReportErrorCode.SOURCE_HISTORY_UNAVAILABLE);
				}
				recovery.ensureComplete(run.getYearMonth());
				run.sourceReady();
			} catch (RuntimeException e) {
				MonthlyReportFailureLogger.logFailure(run.getId(), null, null, "sources", e);
				run.block(resolveErrorCode(e));
				return;
			}
		}
		MonthlyReportDraft draft = drafts
			.findFirstByRunIdAndStateOrderByIdMemberId(run.getId(), MonthlyReportDraft.State.PENDING)
			.orElse(null);
		if (draft == null && !run.isScanComplete()) {
			Slice<Long> page = run.getTargetMemberId() == null
				? targets.nextPage(run.getYearMonth(), run.getAfterMemberId(), 1)
				: new SliceImpl<>(run.getAfterMemberId() == 0 ? List.of(run.getTargetMemberId()) : List.of());
			if (!page.isEmpty()) {
				Long memberId = page.getContent().get(0);
				draft = drafts.save(MonthlyReportDraft.create(run.getId(), memberId, run.getYearMonth()));
				run.advance(memberId);
			}
		}
		if (draft == null) {
			run.reviewed();
			return;
		}
		if (!targets.isEligible(draft.getId().getMemberId(), run.getYearMonth())) {
			draft.finish(false);
			return;
		}
		draft.begin();
		try {
			MonthlyReportGenerationService.Prepared value = preparation.prepare(draft.getId().getMemberId(),
				run.getYearMonth(), run.getAssetVersion());
			draft.prepare(codec.write(value.report(), draft.getId().getMemberId(), run.getYearMonth()),
				value.metrics().toString(), value.generatedAt());
		} catch (RuntimeException e) {
			MonthlyReportFailureLogger.logFailure(run.getId(), draft.getId().getMemberId(), null, "prepare", e);
			draft.fail(resolveErrorCode(e), false);
		}
	}

	private void publish(MonthlyReportRun run) {
		MonthlyReportDraft draft = drafts
			.findFirstByRunIdAndStateOrderByIdMemberId(run.getId(), MonthlyReportDraft.State.PREPARED)
			.orElse(null);
		if (draft == null) {
			if (drafts.countByRunIdAndState(run.getId(), MonthlyReportDraft.State.PUBLISH_FAILED) > 0) {
				run.block("PUBLICATION_FAILED");
			} else {
				run.complete();
			}
			return;
		}
		draft.begin();
		try {
			boolean created = publisher.publish(draft, run.getPushAt());
			boolean published = created
				|| reports.findById(new MonthlyReportMemberId(draft.getId().getMemberId(), run.getYearMonth()))
					.filter(value -> value.getStatus() == MonthlyReportStatus.PUBLISHED).isPresent();
			draft.finish(published);
		} catch (RuntimeException e) {
			MonthlyReportFailureLogger.logFailure(run.getId(), draft.getId().getMemberId(), null, "publish", e);
			draft.fail(resolveErrorCode(e), true);
		}
	}

	private String resolveErrorCode(RuntimeException exception) {
		return exception instanceof CustomException custom ? custom.getErrorCode().getCode()
			: "REPORT_OPERATION_FAILED";
	}
}
