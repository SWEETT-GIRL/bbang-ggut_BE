package sweetgirl.croissant.breadlog.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPhotoRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordTagRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlInfo;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.exception.S3StorageException;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class BreadRecordCommandService {

	private final S3Service s3Service;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final StoreRepository storeRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordPhotoRepository photoRepository;
	private final BreadRecordTagRepository tagRepository;
	private final BreadCategoryClassifier categoryClassifier;
	private final BreadRecordRegionResolver regionResolver;
	private final ImageProcessingCallbackService imageProcessingCallbackService;
	private final CourseProgressService courseProgressService;

	public BreadRecordPresignedUrlResponse generatePresignedUrls(
			Long memberId,
			BreadRecordPresignedUrlRequest request) {
		memberReader.getMember(memberId);
		List<BreadRecordPresignedUrlInfo> presignedUrls = request.mimeTypes().stream()
			.map(mimeType -> generatePresignedUrl(memberId, mimeType))
			.toList();
		return new BreadRecordPresignedUrlResponse(presignedUrls);
	}

	public BreadRecordCreateResponse create(Long memberId, BreadRecordUpsertRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		ValidatedRecordData data = validate(memberId, request);
		BreadRecord record = breadRecordRepository.save(BreadRecord.builder()
			.member(member)
			.store(data.store())
			.storeName(data.storeName())
			.regionKey(data.regionKey())
			.memo(normalizeNullable(request.memo()))
			.lovedBreads(data.lovedBreads())
			.eatenAt(request.eatenAt())
			.build());
		savePhotosAndTags(record, request, data.photoBySortOrder(), Map.of());
		// 찜은 사용자가 직접 풀 때까지 남긴다 — 기록이 생겨도 "또 가고 싶은 가게"일 수 있다.
		// (기록 시 자동 해제는 정책 변경으로 제거)
		courseProgressService.refreshForMember(memberId);
		return new BreadRecordCreateResponse(record.getId(), record.getCreatedAt());
	}

	public void replace(Long recordId, Long memberId, BreadRecordUpsertRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		ValidatedRecordData data = validate(memberId, request);
		List<BreadRecordPhoto> oldPhotos = photoRepository.findAllIncludingPendingByRecordId(recordId);
		Map<String, BreadRecordPhoto> oldPhotoByKey = oldPhotos.stream()
			.collect(java.util.stream.Collectors.toMap(BreadRecordPhoto::getAwsKey, photo -> photo));

		record.update(
			data.store(),
			data.storeName(),
			data.regionKey(),
			normalizeNullable(request.memo()),
			data.lovedBreads(),
			request.eatenAt());
		tagRepository.deleteAllByRecordId(recordId);
		photoRepository.deleteAllIncludingPendingByRecordId(recordId);
		photoRepository.flush();
		savePhotosAndTags(record, request, data.photoBySortOrder(), oldPhotoByKey);
		deleteRemovedImages(oldPhotos, request.photos());
		courseProgressService.refreshForMember(memberId);
	}

	public void delete(Long recordId, Long memberId) {
		Member member = memberReader.getMember(memberId);
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		List<BreadRecordPhoto> photos = photoRepository.findAllIncludingPendingByRecordId(recordId);
		tagRepository.deleteAllByRecordId(recordId);
		photoRepository.deleteAllIncludingPendingByRecordId(recordId);
		photos.forEach(photo -> safeDelete(photo.getAwsKey()));
		record.softDelete();
		courseProgressService.refreshForMember(memberId);
	}

	private BreadRecordPresignedUrlInfo generatePresignedUrl(Long memberId, String mimeType) {
		validateMimeType(mimeType);
		PresignedUrlResponse response = s3Service.generatePresignedUrl(
			S3PathGenerator.getBreadRecordImagePath(memberId),
			mimeType);
		return new BreadRecordPresignedUrlInfo(
			response.fileName(),
			response.contentType(),
			response.uploadUrl(),
			response.awsKey());
	}

	private ValidatedRecordData validate(Long memberId, BreadRecordUpsertRequest request) {
		Store store = request.storeId() == null ? null : storeRepository.findById(request.storeId())
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		Map<Integer, BreadRecordPhotoRequest> photoBySortOrder = validatePhotos(memberId, request.photos());
		validateTags(request.tags(), photoBySortOrder.keySet());
		List<String> lovedBreads = resolveLovedBreads(request);
		validateLovedBreads(lovedBreads, request.tags());
		String storeName = store != null ? store.getName() : request.storeName().strip();
		return new ValidatedRecordData(
			store,
			storeName,
			regionResolver.resolve(store, request.regionKey()),
			photoBySortOrder,
			lovedBreads);
	}

	/** 새 앱은 lovedBreads(복수), 구버전 앱은 lovedBread(단일)를 보낸다. 빈 값은 버리고 중복은 한 번만 센다. */
	private List<String> resolveLovedBreads(BreadRecordUpsertRequest request) {
		List<String> raw = request.lovedBreads() != null
			? request.lovedBreads()
			: request.lovedBread() != null ? List.of(request.lovedBread()) : List.of();
		return raw.stream()
			.map(this::normalizeNullable)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
	}

	private Map<Integer, BreadRecordPhotoRequest> validatePhotos(
			Long memberId,
			List<BreadRecordPhotoRequest> photos) {
		Map<Integer, BreadRecordPhotoRequest> photoBySortOrder = new HashMap<>();
		String expectedPrefix = S3PathGenerator.getBreadRecordImagePath(memberId) + "/";
		for (BreadRecordPhotoRequest photo : photos) {
			validateMimeType(photo.contentType());
			if (!photo.awsKey().startsWith(expectedPrefix)) {
				throw new BreadLogException(BreadLogErrorCode.INVALID_IMAGE_KEY);
			}
			if (photoBySortOrder.put(photo.sortOrder(), photo) != null) {
				throw new BreadLogException(BreadLogErrorCode.DUPLICATE_PHOTO_ORDER);
			}
		}
		return photoBySortOrder;
	}

	private void validateTags(List<BreadRecordTagRequest> tags, Set<Integer> photoSortOrders) {
		for (BreadRecordTagRequest tag : tags) {
			if (!photoSortOrders.contains(tag.photoSortOrder())) {
				throw new BreadLogException(BreadLogErrorCode.TAG_PHOTO_NOT_FOUND);
			}
			if (!Double.isFinite(tag.positionX()) || !Double.isFinite(tag.positionY())
				|| tag.positionX() < 0 || tag.positionX() > 1
				|| tag.positionY() < 0 || tag.positionY() > 1) {
				throw new BreadLogException(BreadLogErrorCode.INVALID_TAG_POSITION);
			}
		}
	}

	private void validateLovedBreads(List<String> lovedBreads, List<BreadRecordTagRequest> tags) {
		if (lovedBreads.isEmpty()) {
			return;
		}
		Set<String> taggedNames = tags.stream()
			.map(BreadRecordTagRequest::name)
			.map(String::strip)
			.collect(Collectors.toSet());
		if (!taggedNames.containsAll(lovedBreads)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_LOVED_BREAD);
		}
	}

	private void savePhotosAndTags(
			BreadRecord record,
			BreadRecordUpsertRequest request,
			Map<Integer, BreadRecordPhotoRequest> requestedPhotoByOrder,
			Map<String, BreadRecordPhoto> oldPhotoByKey) {
		Map<Integer, BreadRecordPhoto> photoBySortOrder = new HashMap<>();
		List<BreadRecordPhoto> photos = requestedPhotoByOrder.values().stream()
			.sorted(java.util.Comparator.comparingInt(BreadRecordPhotoRequest::sortOrder))
			.map(photoRequest -> toPhoto(record, photoRequest, oldPhotoByKey.get(photoRequest.awsKey())))
			.toList();
		photoRepository.saveAll(photos).forEach(photo -> photoBySortOrder.put(photo.getSortOrder(), photo));
		imageProcessingCallbackService.applyPendingCallbacks(photos);

		List<BreadRecordTag> tags = request.tags().stream()
			.map(tag -> BreadRecordTag.builder()
				.record(record)
				.photo(photoBySortOrder.get(tag.photoSortOrder()))
				.name(tag.name().strip())
				.breadCategory(categoryClassifier.classify(tag.name()))
				.positionX(tag.positionX())
				.positionY(tag.positionY())
				.build())
			.toList();
		tagRepository.saveAll(tags);
	}

	private BreadRecordPhoto toPhoto(
			BreadRecord record,
			BreadRecordPhotoRequest request,
			BreadRecordPhoto oldPhoto) {
		BreadRecordPhoto photo = BreadRecordPhoto.builder()
			.record(record)
			.name(request.fileName())
			.mimeType(toMimeType(request.contentType()))
			.awsKey(request.awsKey())
			.sortOrder(request.sortOrder())
			.width(request.width())
			.height(request.height())
			.build();
		if (oldPhoto != null && oldPhoto.getProcessingStatus() == ImageStatus.DONE) {
			photo.markDone();
			photo.updateResizedKeys(photo.getAwsKey());
		} else if (oldPhoto != null && oldPhoto.getProcessingStatus() == ImageStatus.FAILED) {
			photo.markFailed();
		}
		return photo;
	}

	private void deleteRemovedImages(
			List<BreadRecordPhoto> oldPhotos,
			List<BreadRecordPhotoRequest> requestedPhotos) {
		Set<String> requestedKeys = requestedPhotos.stream()
			.map(BreadRecordPhotoRequest::awsKey)
			.collect(java.util.stream.Collectors.toCollection(HashSet::new));
		oldPhotos.stream()
			.map(BreadRecordPhoto::getAwsKey)
			.filter(key -> !requestedKeys.contains(key))
			.forEach(this::safeDelete);
	}

	private BreadRecord getOwnedRecord(Long recordId, Long memberId) {
		BreadRecord record = breadRecordRepository.findByIdAndDeletedAtIsNull(recordId)
			.orElseThrow(() -> new BreadLogException(BreadLogErrorCode.BREAD_RECORD_NOT_FOUND));
		if (!record.getMember().getId().equals(memberId)) {
			throw new BreadLogException(BreadLogErrorCode.BREAD_RECORD_ACCESS_DENIED);
		}
		return record;
	}

	private MimeType toMimeType(String mimeType) {
		try {
			return MimeType.fromString(mimeType.toLowerCase(Locale.ROOT));
		} catch (S3StorageException exception) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_IMAGE_MIME_TYPE);
		}
	}

	private void validateMimeType(String mimeType) {
		if (mimeType == null) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_IMAGE_MIME_TYPE);
		}
		toMimeType(mimeType);
	}

	private String normalizeNullable(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private void safeDelete(String awsKey) {
		try {
			s3Service.deleteObject(awsKey);
		} catch (RuntimeException exception) {
			log.warn("빵 기록 이미지 S3 삭제 실패 [key={}]: {}", awsKey, exception.getMessage());
		}
	}

	private record ValidatedRecordData(
			Store store,
			String storeName,
			String regionKey,
			Map<Integer, BreadRecordPhotoRequest> photoBySortOrder,
			List<String> lovedBreads) {
	}
}
