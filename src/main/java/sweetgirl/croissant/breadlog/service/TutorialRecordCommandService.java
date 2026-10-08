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
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.entity.MemberTutorialCompletion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadlog.repository.MemberTutorialCompletionRepository;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.service.WorldCommandService;

@Service
@RequiredArgsConstructor
@Transactional
public class TutorialRecordCommandService {
	private final MemberRepository memberRepository;
	private final MemberRestrictionReader restrictions;
	private final MemberTutorialCompletionRepository completions;
	private final BreadRecordRepository records;
	private final BreadRecordPhotoRepository photos;
	private final BreadRecordTagRepository tags;
	private final WorldCommandService world;
	private final MemberWorldRepository memberWorldRepository;

	@Value("${tutorial.enabled:false}")
	private boolean enabled;

	public TutorialRecordResponse complete(Long memberId, TutorialRecordRequest request) {
		Member member = lockMember(memberId);
		restrictions.validateNotRestricted(memberId);
		var previous = completions.findById(memberId);
		if (previous.isPresent()) {
			Long recordId = previous.get().getRecordId();
			return new TutorialRecordResponse(false, true, recordId,
				records.findByIdAndDeletedAtIsNull(recordId).isEmpty());
		}
		if (!enabled) {
			throw new BreadLogException(BreadLogErrorCode.TUTORIAL_UNAVAILABLE);
		}
		if (!member.isAgreeToTerms() || !member.isAgreeToPrivacy() || !member.isAgreeToAgeOver14()) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
		if (!TutorialRecordTemplate.CODE.equals(request.templateCode())
			|| !Double.isFinite(request.x()) || !Double.isFinite(request.y())
			|| request.x() < 0 || request.x() > 1 || request.y() < 0 || request.y() > 1) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
		validateBread(request.towerBread());
		BreadRecord record = BreadRecord.builder().member(member)
			.storeName(TutorialRecordTemplate.STORE_NAME).memo(request.memo())
			.eatenAt(request.eatenAt()).towerBread(request.towerBread()).lovedBreads(List.of()).build();
		record.markTutorial(TutorialRecordTemplate.CODE);
		records.saveAndFlush(record);
		BreadRecordPhoto photo = BreadRecordPhoto.builder().record(record)
			.name("bbangdaengi-v1.png").mimeType(MimeType.IMAGE_PNG)
			.awsKey(TutorialRecordTemplate.PHOTO_KEY).sortOrder(0).width(1254).height(1254).build();
		photo.markDone();
		photos.save(photo);
		BreadRecordTag tag = BreadRecordTag.builder().record(record).photo(photo)
			.name(TutorialRecordTemplate.BREAD_NAME).breadCategory(TutorialRecordTemplate.BREAD_NAME)
			.positionX(request.x()).positionY(request.y()).style(request.style()).build();
		if (request.rating() != null) {
			var rating = request.rating();
			tag.rate(rating.again(), rating.textures(), rating.tastes(), rating.memo());
		}
		tags.save(tag);
		completions.save(new MemberTutorialCompletion(memberId, record.getId(), TutorialRecordTemplate.CODE));
		world.syncProgress(memberId);
		memberWorldRepository.raiseMaxRecordCount(memberId, records.countByMemberIdAndDeletedAtIsNull(memberId));
		return new TutorialRecordResponse(true, true, record.getId(), false);
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
