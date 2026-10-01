package sweetgirl.croissant.breadlog.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;

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
	private final MenuRepository menuRepository;
	private final BreadCategoryClassifier categoryClassifier;
	private final BreadRecordRegionResolver regionResolver;
	private final ImageProcessingCallbackService imageProcessingCallbackService;
	private final CourseProgressService courseProgressService;
	private final MemberWorldRepository memberWorldRepository;

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
		resolveOriginalPhotos(request.photos(), List.of());
		BreadRecord record = breadRecordRepository.save(BreadRecord.builder()
			.member(member)
			.store(data.store())
			.storeName(data.storeName())
			.regionKey(data.regionKey())
			.memo(normalizeNullable(request.memo()))
			.lovedBreads(data.lovedBreads())
			.towerBread(request.towerBread())
			.eatenAt(request.eatenAt())
			.isPublic(Boolean.TRUE.equals(request.isPublic()))
			.build());
		savePhotosAndTags(record, request, data.photoBySortOrder(), Map.of());
		// 빵탑은 기록 수만큼 오른다. 최고 높이는 내려가지 않아서, 기록을 지워도 행성이 밀리지 않는다.
		memberWorldRepository.raiseMaxRecordCount(
			memberId, breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId));
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
		Map<String, BreadRecordPhoto> originalByRequestedKey = resolveOriginalPhotos(request.photos(), oldPhotos);

		record.update(
			data.store(),
			data.storeName(),
			data.regionKey(),
			normalizeNullable(request.memo()),
			data.lovedBreads(),
			request.towerBread(),
			request.eatenAt());
		if (request.isPublic() != null) {
			record.changeVisibility(request.isPublic());
		}
		tagRepository.deleteAllByRecordId(recordId);
		photoRepository.deleteAllIncludingPendingByRecordId(recordId);
		photoRepository.flush();
		savePhotosAndTags(record, request, data.photoBySortOrder(), originalByRequestedKey);
		deleteRemovedImages(oldPhotos, request.photos());
		courseProgressService.refreshForMember(memberId);
	}

	public void changeVisibility(Long recordId, Long memberId, boolean isPublic) {
		Member member = memberReader.getMember(memberId);
		// 공개는 남에게 보이는 글을 올리는 것과 같다 — 작성 제한 중엔 공개로 못 돌린다. 비공개로 내리는 건 막지 않는다.
		if (isPublic) {
			memberRestrictionReader.validateCanWrite(memberId);
		}
		getOwnedRecord(recordId, member.getId()).changeVisibility(isPublic);
	}

	public void delete(Long recordId, Long memberId) {
		Member member = memberReader.getMember(memberId);
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		List<BreadRecordPhoto> photos = photoRepository.findAllIncludingPendingByRecordId(recordId);
		tagRepository.deleteAllByRecordId(recordId);
		photoRepository.deleteAllIncludingPendingByRecordId(recordId);
		photos.forEach(photo -> safeDelete(photo.getAwsKey()));
		record.softDelete();
		// 기록이 사라지면 그 기록으로 켜둔 코스 방문 체크도 풀어야 한다.
		// 집계를 다시 돌리기 전에 끊어야 진행률에 반영된다.
		courseProgressService.detachVisitsByRecord(recordId);
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

		Map<Long, Menu> menuById = findMenusById(record.getStore(), request.tags());
		Map<String, Menu> menuByName = findMenusByName(record.getStore(), request.tags(), menuById);
		List<BreadRecordTag> tags = request.tags().stream()
			.map(tag -> BreadRecordTag.builder()
				.record(record)
				.photo(photoBySortOrder.get(tag.photoSortOrder()))
				.menu(resolveMenu(tag, menuById, menuByName))
				.suggestedMenu(resolveSuggestedMenu(tag, menuById))
				.name(tag.name().strip())
				.breadCategory(categoryClassifier.classify(tag.name()))
				.positionX(tag.positionX())
				.positionY(tag.positionY())
				.style(tag.style())
				.build())
			.toList();
		tagRepository.saveAll(tags);
	}

	/**
	 * 태그가 가리킬 메뉴. 앱이 실어 보낸 menuId 가 먼저고, 그게 없거나 이 가게 메뉴가 아니면 이름으로 찾는다.
	 *
	 * <p>앱이 보낸 id 를 못 쓰더라도 예외를 던지지 않는다 — 메뉴가 그 사이 지워졌을 수 있고,
	 * 태그 하나가 메뉴를 못 가리키는 것 때문에 기록 저장을 막을 이유가 없다. 못 찾으면 null 이고,
	 * 그 태그는 저장된 breadCategory(룰 판정)로만 산다.
	 */
	private Menu resolveMenu(BreadRecordTagRequest tag, Map<Long, Menu> menuById, Map<String, Menu> menuByName) {
		if (tag.menuId() != null) {
			Menu requested = menuById.get(tag.menuId());
			if (requested != null) {
				return requested;
			}
		}
		return menuByName.get(MenuNameNormalizer.normalize(tag.name()));
	}

	/**
	 * 그 자리에 AI 가 제안했던 메뉴. 이름으로 물러서지 않는다 — 제안은 언제나 메뉴 id 로 오고,
	 * 이 가게 메뉴가 아니면 남길 것도 없다. 쌓아 두기만 하는 값이라 못 찾아도 그냥 null 이다.
	 */
	private Menu resolveSuggestedMenu(BreadRecordTagRequest tag, Map<Long, Menu> menuById) {
		return tag.suggestedMenuId() == null ? null : menuById.get(tag.suggestedMenuId());
	}

	private Map<Long, Menu> findMenusById(Store store, List<BreadRecordTagRequest> tags) {
		if (store == null) {
			return Map.of();
		}
		// 유저가 고른 메뉴와 AI 가 제안했던 메뉴를 한 번에 가져온다 — 둘 다 이 가게 메뉴여야 한다
		Set<Long> menuIds = tags.stream()
			.flatMap(tag -> Stream.of(tag.menuId(), tag.suggestedMenuId()))
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
		if (menuIds.isEmpty()) {
			return Map.of();
		}
		return menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(store.getId(), menuIds).stream()
			.collect(Collectors.toMap(Menu::getId, menu -> menu));
	}

	/** menuId 로 이미 짚은 태그는 빼고, 남은 이름만 한 번에 묻는다 */
	private Map<String, Menu> findMenusByName(
			Store store,
			List<BreadRecordTagRequest> tags,
			Map<Long, Menu> menuById) {
		if (store == null) {
			return Map.of();
		}
		Set<String> normalizedNames = tags.stream()
			.filter(tag -> tag.menuId() == null || !menuById.containsKey(tag.menuId()))
			.map(tag -> MenuNameNormalizer.normalize(tag.name()))
			.filter(normalizedName -> !normalizedName.isEmpty())
			.collect(Collectors.toSet());
		if (normalizedNames.isEmpty()) {
			return Map.of();
		}
		// 2026-05-27 이전 수기 중복이 남아 있어 같은 이름이 둘 이상 올 수 있다 — 오래된 것을 쓴다
		return menuRepository
			.findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(store.getId(), normalizedNames).stream()
			.collect(Collectors.toMap(
				Menu::getNormalizedName,
				menu -> menu,
				(oldest, duplicate) -> oldest.getId() <= duplicate.getId() ? oldest : duplicate));
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
		if (oldPhoto != null) {
			photo.retainReferenceFrom(oldPhoto);
		}
		if (oldPhoto == null || !oldPhoto.getAwsKey().equals(request.awsKey())) {
			return photo;
		}
		if (oldPhoto.getProcessingStatus() == ImageStatus.DONE) {
			photo.markDone();
			photo.updateResizedKeys(photo.getAwsKey());
		} else if (oldPhoto.getProcessingStatus() == ImageStatus.FAILED) {
			photo.markFailed();
		}
		return photo;
	}

	private Map<String, BreadRecordPhoto> resolveOriginalPhotos(
			List<BreadRecordPhotoRequest> requested, List<BreadRecordPhoto> originals) {
		Map<String, BreadRecordPhoto> resolved = new HashMap<>();
		Set<String> requestedKeys = new HashSet<>();
		Set<BreadRecordPhoto> usedOriginals = new HashSet<>();
		for (BreadRecordPhotoRequest photo : requested) {
			if (!requestedKeys.add(photo.awsKey())) {
				throw new BreadLogException(BreadLogErrorCode.INVALID_PHOTO_REFERENCE);
			}
			BreadRecordPhoto matchingKey = originals.stream()
				.filter(original -> original.getAwsKey().equals(photo.awsKey()))
				.findFirst().orElse(null);
			BreadRecordPhoto original = matchingKey;
			if (photo.originalPhotoId() != null) {
				original = originals.stream().filter(candidate -> photo.originalPhotoId().equals(candidate.getId()))
					.findFirst().orElseThrow(() -> new BreadLogException(BreadLogErrorCode.INVALID_PHOTO_REFERENCE));
				if (matchingKey != null && matchingKey != original) {
					throw new BreadLogException(BreadLogErrorCode.INVALID_PHOTO_REFERENCE);
				}
			}
			if (original != null) {
				if (!usedOriginals.add(original)) {
					throw new BreadLogException(BreadLogErrorCode.INVALID_PHOTO_REFERENCE);
				}
				resolved.put(photo.awsKey(), original);
			}
		}
		return resolved;
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
