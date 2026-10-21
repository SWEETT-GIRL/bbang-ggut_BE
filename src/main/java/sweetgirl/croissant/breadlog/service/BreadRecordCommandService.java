package sweetgirl.croissant.breadlog.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPhotoRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordTagRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlInfo;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordUpdateResponse;
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
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.service.CrumbGrantService;
import sweetgirl.croissant.reward.service.RecordRewardService;
import sweetgirl.croissant.reward.service.TourRewardService;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreTrendingSignalEvent;
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
	private final MemberRepository memberRepository;
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
	private final RecordRewardService recordRewardService;
	private final TourRewardService tourRewardService;
	private final ApplicationEventPublisher eventPublisher;

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
		return create(memberId, request, null);
	}

	/**
	 * 빵투어에서 한 번 탭으로 만드는 가벼운 기록 — 가게만 있고 사진도 빵도 없다. 나중에 고쳐서 덧붙인다.
	 *
	 * <p>보통 기록과 두 가지가 다르다. 기록 보상의 멱등 키가 (빵투어, 가게)라서 지우고 다시 눌러도 한 번만 받고,
	 * 급부상 신호를 내지 않는다 — 탭 한 번으로 가게 순위를 밀어 올릴 수 있으면 안 된다.
	 */
	public BreadRecordCreateResponse createTourQuickRecord(Long memberId, Long courseId, Store store, UUID requestId) {
		BreadRecordUpsertRequest request = new BreadRecordUpsertRequest(
			store.getId(), store.getName(), null, null, null, null, null, LocalDateTime.now(), List.of(), List.of(),
			false, requestId);
		return create(memberId, request, new TourStop(courseId, store.getId()));
	}

	private BreadRecordCreateResponse create(Long memberId, BreadRecordUpsertRequest request, TourStop tourStop) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		if (request.requestId() != null) {
			// 같은 요청이 나란히 들어와도 한쪽이 끝난 뒤에 찾도록 회원 행을 잠근다(삭제·로그인 이관과 같은 잠금).
			memberRepository.findActiveForUpdate(memberId)
				.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_DELETED));
			Optional<BreadRecord> alreadyCreated = breadRecordRepository.findByMemberIdAndClientRequestId(
				memberId, request.requestId());
			if (alreadyCreated.isPresent()) {
				// 응답을 못 받아 다시 보낸 저장이다 — 처음 만든 기록을 그대로 돌려주고 보상은 다시 싣지 않는다
				BreadRecord created = alreadyCreated.get();
				return new BreadRecordCreateResponse(created.getId(), created.getCreatedAt(), List.of(),
					BreadRecordCreateResponse.tagsOf(tagRepository.findAllWithChipsByRecordId(created.getId())));
			}
		}
		ValidatedRecordData data = validate(memberId, request);
		int storedMaxHeight = memberWorldRepository.findMaxRecordCount(memberId).orElse(0);
		long recordCountBefore = breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId);
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
			.clientRequestId(request.requestId())
			.revisit(request.revisit())
			.build());
		List<BreadRecordTag> savedTags = savePhotosAndTags(record, request, data.photoBySortOrder(), Map.of(),
			data.lovedBreads(), Map.of(), Map.of());
		// 분류는 메뉴 분류 프록시를 열 수 있어 영속성 컨텍스트가 살아 있을 때 읽는다 — 아래 빵탑 갱신이 컨텍스트를 비운다
		List<BreadRecordCreateResponse.Tag> tags = BreadRecordCreateResponse.tagsOf(savedTags);
		// 빵탑은 기록 수만큼 오른다. 최고 높이는 내려가지 않아서, 기록을 지워도 행성이 밀리지 않는다.
		long recordCountAfter = breadRecordRepository.countByMemberIdAndDeletedAtIsNull(memberId);
		memberWorldRepository.raiseMaxRecordCount(memberId, recordCountAfter);
		int heightBefore = (int)Math.max(storedMaxHeight, recordCountBefore);
		int heightAfter = (int)Math.max(storedMaxHeight, recordCountAfter);
		Long chainFrom = tourStop == null ? validChainSource(memberId, request.chainFromRecordId()) : null;
		List<RewardItemResponse> rewards = new ArrayList<>(tourStop != null
			? recordRewardService.rewardTourRecordCreated(member, record.getId(), tourStop.courseId(),
				tourStop.storeId(), heightBefore, heightAfter)
			: chainFrom != null
				? recordRewardService.rewardChainedCreated(member, record.getId(), request.photos().size(),
					heightBefore, heightAfter, chainFrom)
				: recordRewardService.rewardCreated(member, record.getId(), request.photos().size(), heightBefore,
					heightAfter));
		// 찜은 사용자가 직접 풀 때까지 남긴다 — 기록이 생겨도 "또 가고 싶은 가게"일 수 있다.
		// (기록 시 자동 해제는 정책 변경으로 제거)
		rewards.addAll(tourRewardsAfterRecordChange(member));
		// 가게를 고른 기록만 급부상 신호다 — 가게 없이 이름만 적은 기록은 어느 가게인지 모른다
		if (data.store() != null && tourStop == null) {
			eventPublisher.publishEvent(
				new StoreTrendingSignalEvent(memberId, data.store().getId(), TrendingSignal.RECORD));
		}
		return new BreadRecordCreateResponse(record.getId(), record.getCreatedAt(), rewards, tags);
	}

	/**
	 * 연속 기록의 앞 기록 — 내 기록이고, 지우지 않았고, 저장한 지 {@link RecordRewardService#CHAIN_WINDOW} 안일 때만 인정한다.
	 * 아니면 null — 기록은 그대로 저장되고 보상만 평소대로다(완료 화면을 오래 열어 뒀다 눌러도 실패시키지 않는다).
	 */
	private Long validChainSource(Long memberId, Long chainFromRecordId) {
		if (chainFromRecordId == null) {
			return null;
		}
		LocalDateTime since = LocalDateTime.now().minus(RecordRewardService.CHAIN_WINDOW);
		return breadRecordRepository.findByIdAndDeletedAtIsNull(chainFromRecordId)
			.filter(source -> source.getMember().getId().equals(memberId))
			.filter(source -> source.getCreatedAt() != null && source.getCreatedAt().isAfter(since))
			.map(BreadRecord::getId)
			.orElse(null);
	}

	public BreadRecordUpdateResponse replace(Long recordId, Long memberId, BreadRecordUpsertRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		if (record.isTutorial()) {
			throw new BreadLogException(BreadLogErrorCode.TUTORIAL_EDIT_RESTRICTED);
		}
		ValidatedRecordData data = validate(memberId, request);
		List<BreadRecordPhoto> oldPhotos = photoRepository.findAllIncludingPendingByRecordIdForUpdate(recordId);
		Map<String, BreadRecordPhoto> originalByRequestedKey = resolveOriginalPhotos(request.photos(), oldPhotos);

		record.update(
			data.store(),
			data.storeName(),
			data.regionKey(),
			normalizeNullable(request.memo()),
			data.lovedBreads(),
			request.towerBread(),
			request.eatenAt(),
			request.revisit());
		if (request.isPublic() != null) {
			record.changeVisibility(request.isPublic());
		}
		// 태그는 지우고 다시 만든다 — 평가·AI 제안 메뉴는 이름으로 이어 줘야 수정에 사라지지 않는다
		List<BreadRecordTag> previousTags = tagRepository.findAllWithChipsByRecordId(recordId);
		Map<String, BreadRecordTag.Rating> previousRatingByName = previousRatingsByName(previousTags);
		Map<String, Menu> previousSuggestedMenuByName = previousSuggestedMenusByName(previousTags, data.store());
		tagRepository.deleteAllByRecordId(recordId);
		photoRepository.deleteAllIncludingPendingByRecordId(recordId);
		photoRepository.flush();
		savePhotosAndTags(record, request, data.photoBySortOrder(), originalByRequestedKey, data.lovedBreads(),
			previousRatingByName, previousSuggestedMenuByName);
		deleteRemovedImages(oldPhotos, request.photos());
		// 사진 없이 쓴 기록에 사진을 붙였으면 보너스를 준다(기록한 지 7 일 안, 기록당 한 번)
		List<RewardItemResponse> rewards = new ArrayList<>(recordRewardService.rewardPhotosAttached(
			member, recordId, record.getCreatedAt(), request.photos().size()));
		// 이 사진으로 빵투어의 모든 가게에 사진이 채워졌을 수 있다
		rewards.addAll(tourRewardsAfterRecordChange(member));
		return new BreadRecordUpdateResponse(rewards);
	}

	/** 기록이 바뀐 뒤 빵투어의 완주·사진 완주를 다시 보고, 방금 채워진 것의 보상을 돌려준다. */
	private List<RewardItemResponse> tourRewardsAfterRecordChange(Member member) {
		LocalDate today = LocalDate.now(CrumbGrantService.ZONE);
		List<RewardItemResponse> rewards = new ArrayList<>(tourRewardService.rewardCompletions(
			member, courseProgressService.refreshForMember(member.getId()), today));
		rewards.addAll(tourRewardService.rewardPhotoCompletions(member, today));
		return rewards;
	}

	/** 가벼운 기록이 어느 빵투어의 어느 가게에서 만들어졌는가. */
	private record TourStop(Long courseId, Long storeId) {
	}

	public void changeVisibility(Long recordId, Long memberId, boolean isPublic) {
		Member member = memberReader.getMember(memberId);
		// 공개는 남에게 보이는 글을 올리는 것과 같다 — 작성 제한 중엔 공개로 못 돌린다. 비공개로 내리는 건 막지 않는다.
		if (isPublic) {
			memberRestrictionReader.validateCanWrite(memberId);
		}
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		if (record.isTutorial() && isPublic) {
			throw new BreadLogException(BreadLogErrorCode.TUTORIAL_EDIT_RESTRICTED);
		}
		record.changeVisibility(isPublic);
	}

	public void changeTowerBread(Long recordId, Long memberId, String towerBread) {
		if (!TutorialRecordTemplate.TOWER_BREADS.contains(towerBread)) {
			throw new BreadLogException(BreadLogErrorCode.INVALID_TUTORIAL);
		}
		Member member = memberReader.getMember(memberId);
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		record.changeTowerBread(towerBread);
	}

	public void delete(Long recordId, Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		// 저장·로그인 이관과 같은 잠금을 먼저 잡아 소유권이 바뀐 뒤 옛 계정으로 지우지 못하게 한다.
		Member member = memberRepository.findActiveForUpdate(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_DELETED));
		BreadRecord record = getOwnedRecord(recordId, member.getId());
		if (record.isTutorial()) {
			// 공용 정적 사진과 태그는 보존한다. 완료 이력은 지우지 않아 재지급을 막는다.
			record.softDelete();
			return;
		}
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
			if (tag.photoSortOrder() == null) {
				// 사진에 안 붙은 빵 이름 — 좌표도 없어야 한다
				if (tag.positionX() != null || tag.positionY() != null) {
					throw new BreadLogException(BreadLogErrorCode.INVALID_TAG_POSITION);
				}
				continue;
			}
			if (tag.positionX() == null || tag.positionY() == null) {
				throw new BreadLogException(BreadLogErrorCode.INVALID_TAG_POSITION);
			}
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

	private List<BreadRecordTag> savePhotosAndTags(
			BreadRecord record,
			BreadRecordUpsertRequest request,
			Map<Integer, BreadRecordPhotoRequest> requestedPhotoByOrder,
			Map<String, BreadRecordPhoto> originalByRequestedKey,
			List<String> lovedBreads,
			Map<String, BreadRecordTag.Rating> previousRatingByName,
			Map<String, Menu> previousSuggestedMenuByName) {
		Map<Integer, BreadRecordPhoto> photoBySortOrder = new HashMap<>();
		List<BreadRecordPhoto> photos = requestedPhotoByOrder.values().stream()
			.sorted(java.util.Comparator.comparingInt(BreadRecordPhotoRequest::sortOrder))
			.map(photoRequest -> toPhoto(record, photoRequest, originalByRequestedKey.get(photoRequest.awsKey())))
			.toList();
		photoRepository.saveAll(photos).forEach(photo -> photoBySortOrder.put(photo.getSortOrder(), photo));
		imageProcessingCallbackService.applyPendingCallbacks(photos);

		Map<Long, Menu> menuById = findMenusById(record.getStore(), request.tags());
		Map<String, Menu> menuByName = findMenusByName(record.getStore(), request.tags(), menuById);
		List<BreadRecordTag> tags = request.tags().stream()
			.map(tag -> BreadRecordTag.builder()
				.record(record)
				.photo(tag.photoSortOrder() == null ? null : photoBySortOrder.get(tag.photoSortOrder()))
				.menu(resolveMenu(tag, menuById, menuByName))
				.suggestedMenu(resolveSuggestedMenu(tag, menuById, previousSuggestedMenuByName))
				.name(tag.name().strip())
				.breadCategory(categoryClassifier.classify(tag.name()))
				.positionX(tag.positionX())
				.positionY(tag.positionY())
				.style(tag.style())
				.build())
			.toList();
		Set<String> lovedNames = Set.copyOf(lovedBreads);
		for (int index = 0; index < tags.size(); index++) {
			applyRating(tags.get(index), request.tags().get(index).rating(), previousRatingByName, lovedNames);
		}
		tagRepository.saveAll(tags);
		return tags;
	}

	/**
	 * 태그 평가 — 요청에 실린 것이 먼저다. 없으면(구버전 앱의 수정) 이름 같은 이전 태그의 평가를 이어간다.
	 * 하트(lovedBreads)에 든 이름은 또 먹을래요 on — 평가가 또 먹을래요를 명시하지 않았을 때만.
	 */
	private void applyRating(
			BreadRecordTag tag,
			BreadTagRatingRequest rating,
			Map<String, BreadRecordTag.Rating> previousRatingByName,
			Set<String> lovedNames) {
		if (rating != null) {
			tag.rate(rating.again(), rating.textures(), rating.tastes(), normalizeNullable(rating.memo()));
		} else if (previousRatingByName.containsKey(tag.getName())) {
			tag.rate(previousRatingByName.get(tag.getName()));
		}
		if (lovedNames.contains(tag.getName())) {
			tag.markAgain();
		}
	}

	/** 지우기 전의 태그 평가를 이름별로 — 같은 이름이 둘이면 먼저 단 것. */
	private Map<String, BreadRecordTag.Rating> previousRatingsByName(List<BreadRecordTag> previousTags) {
		Map<String, BreadRecordTag.Rating> ratingByName = new HashMap<>();
		for (BreadRecordTag tag : previousTags) {
			tag.rating().ifPresent(rating -> ratingByName.putIfAbsent(tag.getName(), rating));
		}
		return ratingByName;
	}

	/**
	 * 지우기 전의 AI 제안 메뉴를 이름별로 — 같은 이름이 둘이면 먼저 단 것.
	 * 앱은 응답에서 이 값을 받지 못해 수정 때 다시 보낼 수 없다. 가게를 바꿨으면 이전 가게 메뉴라 버린다.
	 */
	private Map<String, Menu> previousSuggestedMenusByName(List<BreadRecordTag> previousTags, Store store) {
		Map<String, Menu> suggestedMenuByName = new HashMap<>();
		if (store == null) {
			return suggestedMenuByName;
		}
		for (BreadRecordTag tag : previousTags) {
			Menu suggested = tag.getSuggestedMenu();
			if (suggested != null && store.getId().equals(suggested.getStore().getId())) {
				suggestedMenuByName.putIfAbsent(tag.getName(), suggested);
			}
		}
		return suggestedMenuByName;
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
	 * 그 자리에 AI 가 제안했던 메뉴. 메뉴 이름으로 물러서지 않는다 — 제안은 언제나 메뉴 id 로 오고,
	 * 이 가게 메뉴가 아니면 남길 것도 없다. 쌓아 두기만 하는 값이라 못 찾아도 그냥 null 이다.
	 * 수정에서 id 를 안 보냈으면 이름 같은 이전 태그의 제안을 이어간다.
	 */
	private Menu resolveSuggestedMenu(
			BreadRecordTagRequest tag,
			Map<Long, Menu> menuById,
			Map<String, Menu> previousSuggestedMenuByName) {
		if (tag.suggestedMenuId() != null) {
			return menuById.get(tag.suggestedMenuId());
		}
		return previousSuggestedMenuByName.get(tag.name().strip());
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
		BreadRecord record = breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(recordId)
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
		// 같은 파일을 가리키는 사진 행이 다른 기록에 남아 있으면 지우지 않는다 — 한 키에 행이 여럿인 데이터가 실제로 있다.
		if (photoRepository.existsByAwsKey(awsKey)) {
			return;
		}
		try {
			s3Service.deleteObject(awsKey);
			// 원본만 지우면 리사이즈 Lambda 가 만든 크기 조정본이 버킷에 남는다.
			s3Service.deleteObjects(List.of(
				S3PathGenerator.getResizedKey(320, awsKey),
				S3PathGenerator.getResizedKey(640, awsKey),
				S3PathGenerator.getResizedKey(1024, awsKey)));
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
