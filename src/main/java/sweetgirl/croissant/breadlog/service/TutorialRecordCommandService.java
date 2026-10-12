package sweetgirl.croissant.breadlog.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.request.TutorialRecordEditRequest;
import sweetgirl.croissant.breadlog.dto.request.TutorialRecordRequest;
import sweetgirl.croissant.breadlog.dto.response.TutorialRecordResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.entity.MemberTutorialCompletion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadlog.repository.MemberTutorialCompletionRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@Service
@RequiredArgsConstructor
@Transactional
public class TutorialRecordCommandService {
	private final MemberRepository memberRepository;
	private final TutorialTemplateQueryService templates;
	private final MemberRestrictionReader restrictions;
	private final MemberTutorialCompletionRepository completions;
	private final BreadRecordRepository records;
	private final BreadRecordTagRepository tags;

	@Value("${tutorial.enabled:false}")
	private boolean enabled;

	/**
	 * 체험을 마쳤다는 사실만 남긴다. 빵로그는 만들지 않는다 — 준비된 사진으로 따라 해 본 것이 내 기록 목록과
	 * 빵탑·행성 진행도에 섞이면, 사용자가 쓴 적 없는 빵이 첫 기록으로 남는다.
	 *
	 * <p>이 변경 전에 만들어진 체험 기록은 그대로 둔다. 완료 행이 그 기록을 가리키고, 지웠는지는 응답이 알려 준다.
	 */
	public TutorialRecordResponse complete(Long memberId, TutorialRecordRequest request) {
		Member member = lockMember(memberId);
		restrictions.validateNotRestricted(memberId);
		var previous = completions.findById(memberId);
		if (previous.isPresent()) {
			Long recordId = previous.get().getRecordId();
			return new TutorialRecordResponse(false, true, recordId,
				recordId != null && records.findByIdAndDeletedAtIsNull(recordId).isEmpty());
		}
		if (!enabled) {
			throw new BreadLogException(BreadLogErrorCode.TUTORIAL_UNAVAILABLE);
		}
		if (!member.isAgreeToTerms() || !member.isAgreeToPrivacy() || !member.isAgreeToAgeOver14()) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
		templates.published(request.templateCode());
		completions.save(new MemberTutorialCompletion(memberId, null, request.templateCode()));
		return new TutorialRecordResponse(true, true, null, false);
	}

	public void edit(Long memberId, Long recordId, TutorialRecordEditRequest request) {
		lockMember(memberId);
		restrictions.validateNotRestricted(memberId);
		BreadRecord record = ownedTutorial(memberId, recordId);
		validateBread(request.towerBread());
		record.editTutorial(request.memo(), request.towerBread());
		for (BreadRecordTag tag : tags.findAllWithChipsByRecordId(recordId)) {
			var rating = request.rating();
			tag.rate(rating == null ? null : rating.again(), rating == null ? List.of() : rating.textures(),
				rating == null ? List.of() : rating.tastes(), rating == null ? null : rating.memo());
		}
	}

	private BreadRecord ownedTutorial(Long memberId, Long recordId) {
		BreadRecord record = records.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		if (!record.getMember().getId().equals(memberId) || !record.isTutorial()) {
			throw new BreadLogException(BreadLogErrorCode.BREAD_RECORD_ACCESS_DENIED);
		}
		return record;
	}

	private Member lockMember(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		return memberRepository.findActiveForUpdate(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_DELETED));
	}

	private void validateBread(String bread) {
		if (!TutorialRecordTemplate.TOWER_BREADS.contains(bread)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
	}
}
