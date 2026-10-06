package sweetgirl.croissant.monthlyreport.admin.service;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportMemberResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportProgressResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportRunResponse;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportAdminQueryService {
	private final MonthlyReportRunRepository runs;
	private final MonthlyReportDraftRepository drafts;
	private final MonthlyReportPayloadCodec codec;
	private final MemberReader members;
	private final MonthlyReportQueryService queries;

	public SliceResponse<MonthlyReportRunResponse> getReportRuns(String month, int page, int size) {
		MonthlyReportMonth.parse(month);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		return SliceResponse.from(runs.findByYearMonthOrderByCreatedAtDescIdDesc(month, PageRequest.of(page, size))
			.map(MonthlyReportRunResponse::from));
	}

	public MonthlyReportProgressResponse getReportRunProgress(String month, UUID id) {
		MonthlyReportRun run = getRequiredReportRun(month, id);
		Map<MonthlyReportDraft.State, Long> counts = new EnumMap<>(MonthlyReportDraft.State.class);
		for (MonthlyReportDraft.State state : MonthlyReportDraft.State.values()) {
			counts.put(state, drafts.countByRunIdAndState(id, state));
		}
		return new MonthlyReportProgressResponse(MonthlyReportRunResponse.from(run), counts);
	}

	public SliceResponse<MonthlyReportMemberResponse> getReportRunMembers(String month, UUID id, int page, int size) {
		getRequiredReportRun(month, id);
		if (page < 0 || size < 1 || size > 100) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_PAGE);
		}
		return SliceResponse.from(drafts.findByRunIdOrderByIdMemberId(id, PageRequest.of(page, size))
			.map(value -> new MonthlyReportMemberResponse(value.getId().getMemberId(), value.getState(),
				value.getAttempts(),
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
		return queries.resolveCurrentImages(codec.read(draft.getPayload(), memberId, month),
			codec.readPhotoSources(draft.getRawMetrics())).withNickname(members.getMember(memberId).getNickname());
	}

	private MonthlyReportRun getRequiredReportRun(String month, UUID id) {
		MonthlyReportMonth.parse(month);
		return runs.findById(id).filter(run -> run.getYearMonth().equals(month))
			.orElseThrow(() -> new CustomException(MonthlyReportErrorCode.ADMIN_NOT_FOUND));
	}

	private CustomException createRunConflictException() {
		return new CustomException(MonthlyReportErrorCode.ADMIN_CONFLICT);
	}
}
