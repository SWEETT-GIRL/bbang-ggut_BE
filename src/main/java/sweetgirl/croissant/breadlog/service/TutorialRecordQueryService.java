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
	private final MemberTutorialCompletionRepository completions;
	private final BreadRecordRepository records;
	@Value("${tutorial.enabled:false}")
	private boolean enabled;

	public TutorialRecordStateResponse getState(Long memberId) {
		memberReader.getMember(memberId);
		var completion = completions.findById(memberId);
		Long recordId = completion.map(value -> value.getRecordId()).orElse(null);
		return new TutorialRecordStateResponse(enabled, TutorialRecordTemplate.CODE,
			"/" + TutorialRecordTemplate.PHOTO_KEY, TutorialRecordTemplate.STORE_NAME,
			TutorialRecordTemplate.BREAD_NAME, TutorialRecordTemplate.TOWER_BREADS, completion.isPresent(),
			recordId, recordId != null && records.findByIdAndDeletedAtIsNull(recordId).isEmpty());
	}
}
