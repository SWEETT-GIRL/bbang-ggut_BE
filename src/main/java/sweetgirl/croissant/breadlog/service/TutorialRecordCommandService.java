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
	private final TutorialTemplateQueryService templates;
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
		var template = templates.published(request.templateCode());
		BreadRecord record = BreadRecord.builder().member(member)
			.storeName(template.storeName()).memo(template.memo())
			.eatenAt(request.eatenAt()).towerBread(template.towerBread()).lovedBreads(List.of()).build();
		record.markTutorial(request.templateCode());
		records.saveAndFlush(record);
		BreadRecordPhoto photo = BreadRecordPhoto.builder().record(record)
			.name(template.imageKey().substring(template.imageKey().lastIndexOf('/') + 1))
			.mimeType(MimeType.fromString(template.contentType()))
			.awsKey(template.imageKey()).sortOrder(0).width(template.width()).height(template.height()).build();
		photo.markDone();
		photos.save(photo);
		BreadRecordTag tag = BreadRecordTag.builder().record(record).photo(photo)
			.name(template.breadName()).breadCategory(template.breadName())
			.positionX(template.x()).positionY(template.y()).style(template.style()).build();
		var rating = template.rating();
		tag.rate(rating.again(), rating.textures(), rating.tastes(), rating.memo());
		tags.save(tag);
		completions.save(new MemberTutorialCompletion(memberId, record.getId(), request.templateCode()));
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
