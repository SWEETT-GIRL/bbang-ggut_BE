package sweetgirl.croissant.breadlog.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordImportDraftResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordCompanionRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

/**
 * 같이 간 사람으로 태그된 빵로그를 내 것으로 가져오는 초안. 기록을 만들지는 않는다 — 앱이 초안으로 쓰기 화면을 채우고 평소처럼 저장한다.
 *
 * <p>사진은 S3 안에서 내 경로로 복사한다(원본을 지워도 남는다). 복사도 ObjectCreated 라 리사이즈 Lambda 가 다시 돌고,
 * 콜백은 아직 사진 행이 없어 보류됐다가 저장 때 {@code ImageProcessingCallbackService.applyPendingCallbacks} 가 적용한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BreadRecordImportService {

	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordPhotoRepository photoRepository;
	private final BreadRecordTagRepository tagRepository;
	private final BreadRecordCompanionRepository companionRepository;
	private final BreadRecordVisibilityPolicy visibilityPolicy;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final S3Service s3Service;

	/** 게스트 · 작성 제한 403, 없으면 404, 볼 수 없으면 403, 태그된 사람이 아니면(스스로 뺀 사람 포함) 403. */
	public BreadRecordImportDraftResponse draft(Long memberId, Long recordId) {
		// 게스트는 스위치(guest.write-blocked)와 무관하게 늘 막는다
		memberReader.requireLoggedIn(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		visibilityPolicy.validateCanView(memberId, record);
		boolean tagged = companionRepository.findByIdRecordIdAndIdMemberId(recordId, memberId)
			.filter(companion -> !companion.isRemoved())
			.isPresent();
		if (!tagged) {
			throw new BreadLogException(BreadLogErrorCode.IMPORT_NOT_COMPANION);
		}
		String myPath = S3PathGenerator.getBreadRecordImagePath(memberId);
		List<BreadRecordImportDraftResponse.ImportDraftPhoto> photos = photoRepository
			.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(List.of(recordId)).stream()
			.map(photo -> copy(photo, myPath))
			.toList();
		List<BreadRecordImportDraftResponse.ImportDraftTag> tags = tagRepository.findAllWithChipsByRecordId(recordId)
			.stream()
			.map(BreadRecordImportDraftResponse.ImportDraftTag::from)
			.toList();
		return BreadRecordImportDraftResponse.of(record, photos, tags);
	}

	private BreadRecordImportDraftResponse.ImportDraftPhoto copy(BreadRecordPhoto photo, String myPath) {
		String fileName = UUID.randomUUID().toString();
		String awsKey = myPath + "/" + fileName;
		s3Service.copyObject(photo.getAwsKey(), awsKey);
		return new BreadRecordImportDraftResponse.ImportDraftPhoto(fileName, photo.getMimeType().getValue(), awsKey,
			photo.getSortOrder(), photo.getWidth(), photo.getHeight());
	}
}
