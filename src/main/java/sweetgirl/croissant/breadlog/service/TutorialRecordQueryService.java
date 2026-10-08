package sweetgirl.croissant.breadlog.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.TutorialRecordStateResponse;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.MemberTutorialCompletionRepository;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TutorialRecordQueryService {
	private final MemberReader memberReader;
	private final TutorialTemplateQueryService templates;
	private final MemberTutorialCompletionRepository completions;
	private final BreadRecordRepository records;
	@Value("${tutorial.enabled:false}")
	private boolean enabled;

	public TutorialRecordStateResponse getState(Long memberId) {
		memberReader.getMember(memberId);
		var completion = completions.findById(memberId);
		Long recordId = completion.map(value -> value.getRecordId()).orElse(null);
		var active = templates.active();
		return new TutorialRecordStateResponse(enabled && active.isPresent(),
			active.map(value -> value.code()).orElse(null), active.map(value -> value.content()).orElse(null),
			TutorialRecordTemplate.TOWER_BREADS, completion.isPresent(),
			recordId, recordId != null && records.findByIdAndDeletedAtIsNull(recordId).isEmpty());
	}
}
