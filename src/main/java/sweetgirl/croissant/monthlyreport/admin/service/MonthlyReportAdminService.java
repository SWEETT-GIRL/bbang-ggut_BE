package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraftId;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportDraftRepository;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportRunRepository;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportMonth;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTargetReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportAdminService {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportDraftRepository drafts;
	private final MonthlyReportPayloadCodec codec;
	private final MemberReader members;
	private final MonthlyReportQueryService queries;
	private final MonthlyReportTargetReader targets;

	@Transactional
	public MonthlyReportRun createReportRun(String month, String version) {
		return createReportRun(month, version, null);
	}

	@Transactional
	public MonthlyReportRun createReportRun(String month, String version, Long targetMemberId) {
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
		return run;
	}

	/** 여러 서버와 수동 생성이 경합해도 먼저 등록된 작업의 버전과 상태를 유지한다. */
	@Transactional
	public void registerAutomaticReportRun(String month, String version) {
		if (!MonthlyReportMonth.parse(month).isBefore(YearMonth.now(ZoneId.of("Asia/Seoul")))) {
			throw new CustomException(MonthlyReportErrorCode.MONTH_NOT_CLOSED);
		}
		runs.enqueue(UUID.randomUUID(), month, version, Instant.now(), null);
	}

	public SliceResponse<MonthlyReportRun> getReportRuns(String month, int page, int size) {
		MonthlyReportMonth.parse(month);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		return SliceResponse.from(runs.findByYearMonthOrderByCreatedAtDescIdDesc(month, PageRequest.of(page, size)));
	}

	public Progress getReportRunProgress(String month, UUID id) {
		MonthlyReportRun run = getRequiredReportRun(month, id);
		Map<MonthlyReportDraft.State, Long> counts = new EnumMap<>(MonthlyReportDraft.State.class);
		for (MonthlyReportDraft.State state : MonthlyReportDraft.State.values()) {
			counts.put(state, drafts.countByRunIdAndState(id, state));
		}
		return new Progress(run, counts);
	}

	public SliceResponse<Item> getReportRunMembers(String month, UUID id, int page, int size) {
		getRequiredReportRun(month, id);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		return SliceResponse.from(drafts.findByRunIdOrderByIdMemberId(id, PageRequest.of(page, size))
			.map(value -> new Item(value.getId().getMemberId(), value.getState(), value.getAttempts(),
				value.getErrorCode())));
	}

	public MonthlyReportResponse getReportPreview(String month, UUID id, Long memberId) {
		getRequiredReportRun(month, id);
		MonthlyReportDraft draft = drafts.findById(new MonthlyReportDraftId(id, memberId, month))
			.filter(value -> value.getRunId().equals(id))
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ADMIN_NOT_FOUND));
		if (draft.getState() == MonthlyReportDraft.State.PUBLISHED) {
			return queries.getReport(memberId, month);
		}
		if (draft.getPayload() == null) {
			throw createRunConflictException();
		}
		return queries.resolveCurrentPhotos(codec.read(draft.getPayload(), memberId, month),
			codec.readPhotoSources(draft.getRawMetrics())).withNickname(members.getMember(memberId).getNickname());
	}

	@Transactional
	public MonthlyReportRun approveReportPublication(String month, UUID id, Instant pushAt) {
		MonthlyReportRun run = lockReportRun(month, id);
		if (run.getState() == MonthlyReportRun.State.PUBLISHING || run.getState() == MonthlyReportRun.State.COMPLETE) {
			if (!Objects.equals(run.getPushAt(), pushAt)) {
				throw createRunConflictException();
			}
			return run;
		}
		if (run.getState() != MonthlyReportRun.State.REVIEW || !run.isScanComplete()
			|| drafts.countByRunIdAndState(id, MonthlyReportDraft.State.FAILED) > 0) {
			throw createRunConflictException();
		}
		if (pushAt != null && pushAt.isBefore(Instant.now())) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_ADMIN_REQUEST);
		}
		run.approve(pushAt);
		return run;
	}

	@Transactional
	public MonthlyReportRun retryReportRun(String month, UUID id) {
		MonthlyReportRun run = lockReportRun(month, id);
		if (run.getState() != MonthlyReportRun.State.REVIEW && run.getState() != MonthlyReportRun.State.BLOCKED) {
			throw createRunConflictException();
		}
		List<MonthlyReportDraft> failed = drafts.findByRunIdAndStateIn(id,
			List.of(MonthlyReportDraft.State.FAILED, MonthlyReportDraft.State.PUBLISH_FAILED));
		boolean publishing = failed.stream()
			.anyMatch(value -> value.getState() == MonthlyReportDraft.State.PUBLISH_FAILED);
		failed.forEach(MonthlyReportDraft::retry);
		run.retry(publishing);
		return run;
	}

	private MonthlyReportRun getRequiredReportRun(String month, UUID id) {
		MonthlyReportMonth.parse(month);
		return runs.findById(id).filter(run -> run.getYearMonth().equals(month))
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ADMIN_NOT_FOUND));
	}

	private MonthlyReportRun lockReportRun(String month, UUID id) {
		MonthlyReportMonth.parse(month);
		return runs.lock(id).filter(run -> run.getYearMonth().equals(month))
			.orElseThrow(this::createRunConflictException);
	}

	private CustomException createRunConflictException() {
		return new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
	}

	public record Progress(MonthlyReportRun run, Map<MonthlyReportDraft.State, Long> counts) {
	}
	public record Item(Long memberId, MonthlyReportDraft.State state, int attempts, String errorCode) {
	}
}
