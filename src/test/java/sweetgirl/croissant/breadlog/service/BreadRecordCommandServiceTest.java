package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPhotoRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordTagRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordPresignedUrlResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordUpdateResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.service.RecordRewardService;
import sweetgirl.croissant.reward.service.TourRewardService;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.TrendingSignal;
import sweetgirl.croissant.store.event.StoreTrendingSignalEvent;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;

@ExtendWith(MockitoExtension.class)
class BreadRecordCommandServiceTest {
	@Mock
	MemberRepository memberRepository;

	@Test
	void tutorialDeleteKeepsSharedPhotoAndCannotBeMadePublic() {
		Member member = member(7L);
		BreadRecord tutorial = record(11L, member);
		tutorial.markTutorial(TutorialRecordTemplate.CODE);
		given(memberReader.getMember(7L)).willReturn(member);
		given(memberRepository.findActiveForUpdate(7L)).willReturn(Optional.of(member));
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(tutorial));
		assertThatThrownBy(() -> commandService.changeVisibility(11L, 7L, true))
			.isInstanceOf(BreadLogException.class);
		commandService.delete(11L, 7L);
		assertThat(tutorial.getDeletedAt()).isNotNull();
		org.mockito.Mockito.verifyNoInteractions(s3Service, photoRepository, tagRepository);
	}

	@Test
	void cannotDeleteAnotherMembersTutorial() {
		BreadRecord tutorial = record(11L, member(9L));
		tutorial.markTutorial(TutorialRecordTemplate.CODE);
		given(memberRepository.findActiveForUpdate(7L)).willReturn(Optional.of(member(7L)));
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(tutorial));
		assertThatThrownBy(() -> commandService.delete(11L, 7L)).isInstanceOf(BreadLogException.class);
		assertThat(tutorial.getDeletedAt()).isNull();
	}

	@Test
	void delete_removesOriginalAndResizedVariants() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		String awsKey = "bread-records/members/7/images/a";
		BreadRecordPhoto photo = BreadRecordPhoto.builder().record(record).name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG).awsKey(awsKey).sortOrder(0).build();
		given(memberRepository.findActiveForUpdate(7L)).willReturn(Optional.of(member));
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(photoRepository.findAllIncludingPendingByRecordId(11L)).willReturn(List.of(photo));

		commandService.delete(11L, 7L);

		verify(s3Service).deleteObject(awsKey);
		verify(s3Service).deleteObjects(List.of(
			"resized/320/" + awsKey, "resized/640/" + awsKey, "resized/1024/" + awsKey));
		assertThat(record.getDeletedAt()).isNotNull();
	}

	@Test
	void delete_keepsFileStillUsedByAnotherPhotoRow() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		String awsKey = "bread-records/members/7/images/shared";
		BreadRecordPhoto photo = BreadRecordPhoto.builder().record(record).name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG).awsKey(awsKey).sortOrder(0).build();
		given(memberRepository.findActiveForUpdate(7L)).willReturn(Optional.of(member));
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(photoRepository.findAllIncludingPendingByRecordId(11L)).willReturn(List.of(photo));
		given(photoRepository.existsByAwsKey(awsKey)).willReturn(true);

		commandService.delete(11L, 7L);

		verify(photoRepository).deleteAllIncludingPendingByRecordId(11L);
		org.mockito.Mockito.verifyNoInteractions(s3Service);
		assertThat(record.getDeletedAt()).isNotNull();
	}

	@Mock
	S3Service s3Service;
	@Mock
	MemberReader memberReader;
	@Mock
	MemberRestrictionReader memberRestrictionReader;
	@Mock
	StoreRepository storeRepository;
	@Mock
	BreadRecordRepository breadRecordRepository;
	@Mock
	BreadRecordPhotoRepository photoRepository;
	@Mock
	BreadRecordTagRepository tagRepository;
	@Mock
	MenuRepository menuRepository;
	@Mock
	BreadCategoryClassifier categoryClassifier;
	@Mock
	BreadRecordRegionResolver regionResolver;
	@Mock
	ImageProcessingCallbackService imageProcessingCallbackService;
	@Mock
	CourseProgressService courseProgressService;
	@Mock
	MemberWorldRepository memberWorldRepository;
	// 보상 규칙은 RecordRewardServiceTest 가 본다. 여기서는 빈 목록을 돌려주는 목이면 된다
	@Mock
	RecordRewardService recordRewardService;
	@Mock
	TourRewardService tourRewardService;

	@Mock
	ApplicationEventPublisher eventPublisher;

	@InjectMocks
	BreadRecordCommandService commandService;

	@Test
	@DisplayName("create: 먹은 날과 태그 좌표·분류를 보존해 기록을 생성한다")
	void create_savesRecordPhotosAndTags() {
		Member member = member(7L);
		Store store = store(10L);
		BreadRecordUpsertRequest request = request("bread-records/members/7/images/a", 0);
		given(memberReader.getMember(7L)).willReturn(member);
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(regionResolver.resolve(store, "client_region")).willReturn("서울_성동구_성수동");
		given(categoryClassifier.classify("우유식빵")).willReturn("식빵");
		given(breadRecordRepository.save(any())).willAnswer(invocation -> {
			BreadRecord record = invocation.getArgument(0);
			ReflectionTestUtils.setField(record, "id", 11L);
			ReflectionTestUtils.setField(record, "createdAt", LocalDateTime.of(2026, 8, 4, 12, 0));
			return record;
		});
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

		BreadRecordCreateResponse result = commandService.create(7L, request);

		assertThat(result.recordId()).isEqualTo(11L);
		verify(breadRecordRepository).save(argThat(record -> record.getEatenAt().equals(request.eatenAt())
			&& record.getStoreName().equals("밀도 성수")
			&& record.getRegionKey().equals("서울_성동구_성수동")));
		verify(tagRepository).saveAll(argThat(tags -> {
			List<BreadRecordTag> tagList = StreamSupport.stream(tags.spliterator(), false).toList();
			return tagList.size() == 1
				&& tagList.get(0).getBreadCategory().equals("식빵")
				&& tagList.get(0).getPositionX() == 0.31
				&& tagList.get(0).getPositionY() == 0.42;
		}));
		verify(courseProgressService).refreshForMember(7L);
		// 가게를 고른 기록은 급부상 신호다
		verify(eventPublisher).publishEvent(new StoreTrendingSignalEvent(7L, 10L, TrendingSignal.RECORD));
	}

	@Test
	@DisplayName("create: 빵탑에 쌓을 빵을 저장한다")
	void create_savesTowerBread() {
		givenCreatableRecord();

		commandService.create(7L, requestWithTowerBread("소금빵"));

		ArgumentCaptor<BreadRecord> saved = ArgumentCaptor.forClass(BreadRecord.class);
		verify(breadRecordRepository).save(saved.capture());
		assertThat(saved.getValue().getTowerBread()).isEqualTo("소금빵");
	}

	@Test
	@DisplayName("create: 빵탑 빵을 안 보내는 구버전 요청도 그대로 성공한다")
	void create_withoutTowerBread_succeeds() {
		givenCreatableRecord();

		commandService.create(7L, requestWithTowerBread(null));

		ArgumentCaptor<BreadRecord> saved = ArgumentCaptor.forClass(BreadRecord.class);
		verify(breadRecordRepository).save(saved.capture());
		assertThat(saved.getValue().getTowerBread()).isNull();
	}

	@Test
	@DisplayName("create: 기록이 늘면 빵탑의 최고 높이를 올린다")
	void create_raisesMaxRecordCount() {
		givenCreatableRecord();
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(7L)).willReturn(12L);

		commandService.create(7L, requestWithTowerBread("소금빵"));

		verify(memberWorldRepository).raiseMaxRecordCount(7L, 12L);
	}

	@Test
	@DisplayName("create: 최고였던 빵을 여러 개 저장하고 첫 값은 구버전 컬럼에도 비춘다")
	void create_multipleLovedBreads_saved() {
		Member member = member(7L);
		Store store = store(10L);
		BreadRecordUpsertRequest request = new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, "무시되는 단일값", List.of("소금빵", " 우유식빵 ", "소금빵"), null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(
				new BreadRecordPhotoRequest("a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(
				new BreadRecordTagRequest(0, "우유식빵", null, null, 0.31, 0.42, null, null),
				new BreadRecordTagRequest(0, "소금빵", null, null, 0.5, 0.5, null, null)),
			null);
		given(memberReader.getMember(7L)).willReturn(member);
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(regionResolver.resolve(store, "client_region")).willReturn("서울_성동구_성수동");
		given(categoryClassifier.classify(any())).willReturn("기타");
		given(breadRecordRepository.save(any())).willAnswer(invocation -> {
			BreadRecord record = invocation.getArgument(0);
			ReflectionTestUtils.setField(record, "id", 11L);
			ReflectionTestUtils.setField(record, "createdAt", LocalDateTime.of(2026, 8, 4, 12, 0));
			return record;
		});
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

		commandService.create(7L, request);

		verify(breadRecordRepository).save(argThat(record -> record.getLovedBreads().equals(List.of("소금빵", "우유식빵"))
			&& "소금빵".equals(record.getLovedBread())));
	}

	@Test
	@DisplayName("create: 최고였던 빵 중 태그에 없는 이름이 있으면 거절한다")
	void create_lovedBreadNotTagged_throwsException() {
		BreadRecordUpsertRequest request = new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, List.of("우유식빵", "크루아상"), null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(
				new BreadRecordPhotoRequest("a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "우유식빵", null, null, 0.31, 0.42, null, null)), null);
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));

		assertThatThrownBy(() -> commandService.create(7L, request))
			.isInstanceOf(BreadLogException.class)
			.hasMessageContaining(BreadLogErrorCode.INVALID_LOVED_BREAD.getMessage());

		verify(breadRecordRepository, never()).save(any());
	}

	@Test
	@DisplayName("create: 태그가 요청 사진 순서를 가리키지 않으면 저장하지 않는다")
	void create_unknownTagPhoto_throwsException() {
		BreadRecordUpsertRequest request = request("bread-records/members/7/images/a", 1);
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));

		assertThatThrownBy(() -> commandService.create(7L, request))
			.isInstanceOf(BreadLogException.class)
			.hasMessageContaining(BreadLogErrorCode.TAG_PHOTO_NOT_FOUND.getMessage());

		verify(breadRecordRepository, never()).save(any());
	}

	@Test
	@DisplayName("replace: 같은 awsKey 사진은 삭제하지 않고 처리 완료 상태를 이어받는다")
	void replace_sameImage_preservesS3ObjectAndProcessingStatus() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		String awsKey = "bread-records/members/7/images/a";
		BreadRecordPhoto oldPhoto = BreadRecordPhoto.builder()
			.record(record)
			.name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey(awsKey)
			.sortOrder(0)
			.build();
		oldPhoto.markDone();
		oldPhoto.updateResizedKeys(awsKey);
		BreadRecordUpsertRequest request = request(awsKey, 0);
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));
		given(photoRepository.findAllIncludingPendingByRecordIdForUpdate(11L)).willReturn(List.of(oldPhoto));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(categoryClassifier.classify("우유식빵")).willReturn("식빵");

		commandService.replace(11L, 7L, request);

		verify(s3Service, never()).deleteObject(awsKey);
		verify(photoRepository).saveAll(argThat(photos -> {
			List<BreadRecordPhoto> photoList = StreamSupport.stream(photos.spliterator(), false).toList();
			return photoList.size() == 1
				&& photoList.get(0).getReferenceId().equals(oldPhoto.getReferenceId())
				&& photoList.get(0).getProcessingStatus() == ImageStatus.DONE
				&& photoList.get(0).getResizedKey320().equals("resized/320/" + awsKey);
		}));
		verify(courseProgressService).refreshForMember(7L);
	}

	@Test
	void replace_newFilePreservesPhotoReferenceAndPreviousTagRating() {
		BreadRecord record = givenReplaceableRecord(false);
		String oldKey = "bread-records/members/7/images/a";
		String newKey = "bread-records/members/7/images/edited";
		BreadRecordPhoto original = BreadRecordPhoto.builder().record(record).name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG).awsKey(oldKey).sortOrder(0).build();
		ReflectionTestUtils.setField(original, "id", 23L);
		original.markDone();
		original.updateResizedKeys(oldKey);
		given(photoRepository.findAllIncludingPendingByRecordIdForUpdate(11L)).willReturn(List.of(original));
		BreadRecordTag previous = BreadRecordTag.builder()
			.record(record).photo(original).name("소금빵").breadCategory("소금빵")
			.positionX(0.1).positionY(0.1).build();
		previous.rate(true, List.of(TextureChip.촉촉), List.of(TasteChip.고소), "부드러워");
		given(tagRepository.findAllWithChipsByRecordId(11L)).willReturn(List.of(previous));
		BreadRecordUpsertRequest request = withPhotos(List.of(new BreadRecordPhotoRequest(
			"edited.jpg", "image/jpeg", newKey, 0, 800, 600, 23L)));

		commandService.replace(11L, 7L, request);

		verify(photoRepository).saveAll(argThat(photos -> {
			BreadRecordPhoto photo = photos.iterator().next();
			return photo.getReferenceId().equals(original.getReferenceId()) && photo.getAwsKey().equals(newKey)
				&& photo.getProcessingStatus() == ImageStatus.PENDING && photo.getResizedKey640() == null;
		}));
		verify(tagRepository).saveAll(argThat(tags -> {
			BreadRecordTag tag = firstTag(tags);
			return tag.getPhoto().getReferenceId().equals(original.getReferenceId())
				&& tag.getPhoto().getAwsKey().equals(newKey)
				&& Boolean.TRUE.equals(tag.getAgain())
				&& tag.textures().equals(List.of(TextureChip.촉촉))
				&& tag.tastes().equals(List.of(TasteChip.고소))
				&& "부드러워".equals(tag.getRatingMemo());
		}));
		verify(s3Service).deleteObject(oldKey);
		verify(s3Service).deleteObjects(List.of(
			"resized/320/" + oldKey, "resized/640/" + oldKey, "resized/1024/" + oldKey));
	}

	@Test
	void replace_rejectsForeignStaleOrRepeatedOriginalPhotoBeforeDeletingExistingPhotos() {
		var member = member(7L);
		var record = record(11L, member);
		var original = BreadRecordPhoto.builder().record(record).name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG).awsKey("bread-records/members/7/images/a").sortOrder(0).build();
		ReflectionTestUtils.setField(original, "id", 23L);
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));
		given(photoRepository.findAllIncludingPendingByRecordIdForUpdate(11L)).willReturn(List.of(original));
		for (var photos : List.of(
			List.of(
				new BreadRecordPhotoRequest("b", "image/jpeg", "bread-records/members/7/images/b", 0, null, null, 99L)),
			List.of(
				new BreadRecordPhotoRequest("b", "image/jpeg", "bread-records/members/7/images/b", 0, null, null, 23L),
				new BreadRecordPhotoRequest("c", "image/jpeg", "bread-records/members/7/images/c", 1, null, null,
					23L)))) {
			assertThatThrownBy(() -> commandService.replace(11L, 7L, withPhotos(photos)))
				.isInstanceOf(BreadLogException.class)
				.hasFieldOrPropertyWithValue("errorCode", BreadLogErrorCode.INVALID_PHOTO_REFERENCE);
		}
		verify(tagRepository, never()).deleteAllByRecordId(any());
		verify(photoRepository, never()).deleteAllIncludingPendingByRecordId(any());
	}

	private BreadRecordUpsertRequest withPhotos(List<BreadRecordPhotoRequest> photos) {
		var base = requestWithVisibility(null);
		return new BreadRecordUpsertRequest(base.storeId(), base.storeName(), base.regionKey(), base.memo(),
			base.lovedBread(), base.lovedBreads(), base.towerBread(), base.eatenAt(), photos, base.tags(),
			base.isPublic());
	}

	@Test
	@DisplayName("generatePresignedUrls: 회원 전용 경로로 업로드 URL을 발급한다")
	void generatePresignedUrls_usesMemberPath() {
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(s3Service.generatePresignedUrl("bread-records/members/7/images", "image/jpeg"))
			.willReturn(new PresignedUrlResponse(
				"a", "image/jpeg", "https://upload", "bread-records/members/7/images/a"));

		BreadRecordPresignedUrlResponse result = commandService.generatePresignedUrls(
			7L, new BreadRecordPresignedUrlRequest(List.of("image/jpeg")));

		assertThat(result.presignedUrls()).hasSize(1);
		assertThat(result.presignedUrls().get(0).awsKey()).isEqualTo("bread-records/members/7/images/a");
	}

	private BreadRecordUpsertRequest request(String awsKey, int tagPhotoSortOrder) {
		return new BreadRecordUpsertRequest(
			10L,
			"클라이언트 가게명",
			"client_region",
			"맛있었다",
			"우유식빵",
			null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest("a.jpg", "image/jpeg", awsKey, 0, null, null)),
			List.of(new BreadRecordTagRequest(tagPhotoSortOrder, "우유식빵", null, null, 0.31, 0.42, null, null)), null);
	}

	@Test
	@DisplayName("create: 앱이 menuId 를 안 보내도 가게 메뉴에서 같은 이름을 찾아 태그가 가리키게 한다")
	void create_linksMenuByName() {
		Menu menu = menu(99L, "우유식빵");
		givenCreatableRecord();
		given(categoryClassifier.classify("우유식빵")).willReturn("식빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(10L, Set.of("우유식빵")))
			.willReturn(List.of(menu));

		commandService.create(7L, request("bread-records/members/7/images/a", 0));

		verify(tagRepository).saveAll(argThat(tags -> firstTag(tags).getMenu() == menu));
	}

	@Test
	@DisplayName("create: 앱이 보낸 menuId 가 이 가게 메뉴면 이름을 다시 찾지 않고 그것을 가리킨다")
	void create_linksMenuByRequestedId() {
		Menu menu = menu(99L, "소금빵 (1시)");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(10L, Set.of(99L))).willReturn(List.of(menu));

		commandService.create(7L, requestWithTagMenuId(99L));

		verify(tagRepository).saveAll(argThat(tags -> firstTag(tags).getMenu() == menu));
		verify(menuRepository, never()).findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(any(), any());
	}

	@Test
	@DisplayName("create: 앱이 보낸 menuId 가 이 가게 것이 아니면 저장을 막지 않고 이름 매칭으로 물러선다")
	void create_ignoresMenuIdOfAnotherStore() {
		Menu menu = menu(99L, "소금빵");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(10L, Set.of(1234L))).willReturn(List.of());
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(10L, Set.of("소금빵")))
			.willReturn(List.of(menu));

		commandService.create(7L, requestWithTagMenuId(1234L));

		verify(tagRepository).saveAll(argThat(tags -> firstTag(tags).getMenu() == menu));
	}

	@Test
	@DisplayName("create: AI 가 제안했던 메뉴를 suggestedMenu 로 남긴다 — 유저가 그대로 받아들인 경우")
	void create_keepsSuggestedMenuWhenAccepted() {
		Menu menu = menu(99L, "소금빵");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(10L, Set.of(99L))).willReturn(List.of(menu));

		commandService.create(7L, requestWithTagMenus(99L, 99L));

		verify(tagRepository)
			.saveAll(argThat(tags -> firstTag(tags).getMenu() == menu && firstTag(tags).getSuggestedMenu() == menu));
	}

	@Test
	@DisplayName("create: 유저가 제안을 고쳐도 무엇이 제안됐었는지는 남는다 — 자주 틀리는 쌍의 재료다")
	void create_keepsSuggestedMenuWhenCorrected() {
		Menu chosen = menu(99L, "명란바게트");
		Menu suggested = menu(77L, "마늘바게트");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(10L, Set.of(99L, 77L)))
			.willReturn(List.of(chosen, suggested));

		commandService.create(7L, requestWithTagMenus(99L, 77L));

		verify(tagRepository).saveAll(
			argThat(tags -> firstTag(tags).getMenu() == chosen && firstTag(tags).getSuggestedMenu() == suggested));
	}

	@Test
	@DisplayName("create: suggestedMenuId 를 안 보내면 null 이다 — 직접 단 태그와 구버전 앱이 그렇다")
	void create_leavesSuggestedMenuNullWhenAbsent() {
		Menu menu = menu(99L, "소금빵");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(10L, Set.of(99L))).willReturn(List.of(menu));

		commandService.create(7L, requestWithTagMenuId(99L));

		verify(tagRepository).saveAll(argThat(tags -> firstTag(tags).getSuggestedMenu() == null));
	}

	@Test
	@DisplayName("create: 제안된 메뉴가 이 가게 것이 아니면 그냥 안 남긴다 — 저장을 막지 않는다")
	void create_ignoresSuggestedMenuOfAnotherStore() {
		Menu menu = menu(99L, "소금빵");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(menuRepository.findByStoreIdAndDeletedAtIsNullAndIdIn(10L, Set.of(99L, 1234L)))
			.willReturn(List.of(menu));

		commandService.create(7L, requestWithTagMenus(99L, 1234L));

		verify(tagRepository)
			.saveAll(argThat(tags -> firstTag(tags).getMenu() == menu && firstTag(tags).getSuggestedMenu() == null));
	}

	@Test
	@DisplayName("create: 가게를 안 고른 기록은 메뉴를 찾지 않는다")
	void create_withoutStore_skipsMenuLookup() {
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(regionResolver.resolve(null, "client_region")).willReturn(null);
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(breadRecordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

		commandService.create(7L, new BreadRecordUpsertRequest(
			null, "직접 적은 가게", "client_region", null, null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "소금빵", 99L, null, 0.5, 0.5, null, null)), null));

		verify(tagRepository).saveAll(argThat(tags -> firstTag(tags).getMenu() == null));
		verify(menuRepository, never()).findByStoreIdAndDeletedAtIsNullAndIdIn(any(), any());
		verify(menuRepository, never()).findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(any(), any());
		// 어느 가게인지 모르니 급부상 신호도 없다
		verify(eventPublisher, never()).publishEvent(any(StoreTrendingSignalEvent.class));
	}

	@Test
	@DisplayName("create: 공개 여부를 안 보내면(구버전 앱) 비공개로 저장한다")
	void create_defaultsToPrivate_whenIsPublicMissing() {
		givenCreatableRecord();

		commandService.create(7L, requestWithVisibility(null));

		verify(breadRecordRepository).save(argThat(record -> !record.isPublic()));
	}

	@Test
	@DisplayName("create: 공개로 보내면 공개로 저장한다")
	void create_savesPublic_whenRequested() {
		givenCreatableRecord();

		commandService.create(7L, requestWithVisibility(true));

		verify(breadRecordRepository).save(argThat(BreadRecord::isPublic));
	}

	@Test
	@DisplayName("replace: 공개 여부를 안 보내면(구버전 앱) 지금 값을 그대로 둔다")
	void replace_keepsVisibility_whenIsPublicMissing() {
		BreadRecord record = givenReplaceableRecord(true);

		commandService.replace(11L, 7L, requestWithVisibility(null));

		assertThat(record.isPublic()).isTrue();
	}

	@Test
	@DisplayName("replace: 공개 여부를 보내면 그 값으로 바꾼다")
	void replace_changesVisibility_whenGiven() {
		BreadRecord record = givenReplaceableRecord(true);

		commandService.replace(11L, 7L, requestWithVisibility(false));

		assertThat(record.isPublic()).isFalse();
	}

	@Test
	@DisplayName("changeVisibility: 공개로 돌리면 작성 제한을 확인하고 공개로 바꾼다")
	void changeVisibility_toPublic_checksWriteRestriction() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));

		commandService.changeVisibility(11L, 7L, true);

		assertThat(record.isPublic()).isTrue();
		verify(memberRestrictionReader).validateCanWrite(7L);
	}

	@Test
	@DisplayName("changeVisibility: 비공개로 내리는 건 작성 제한 중에도 막지 않는다")
	void changeVisibility_toPrivate_skipsWriteRestriction() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		record.changeVisibility(true);
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));

		commandService.changeVisibility(11L, 7L, false);

		assertThat(record.isPublic()).isFalse();
		verify(memberRestrictionReader, never()).validateCanWrite(any());
	}

	@Test
	@DisplayName("changeVisibility: 남의 기록이면 접근 거부")
	void changeVisibility_othersRecord_throwsAccessDenied() {
		given(memberReader.getMember(8L)).willReturn(member(8L));
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L))
			.willReturn(Optional.of(record(11L, member(7L))));

		assertThatThrownBy(() -> commandService.changeVisibility(11L, 8L, true))
			.isInstanceOf(BreadLogException.class)
			.hasFieldOrPropertyWithValue("errorCode", BreadLogErrorCode.BREAD_RECORD_ACCESS_DENIED);
	}

	private BreadRecord givenReplaceableRecord(boolean isPublic) {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		record.changeVisibility(isPublic);
		Store store = store(10L);
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(photoRepository.findAllIncludingPendingByRecordIdForUpdate(11L)).willReturn(List.of());
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
		return record;
	}

	private BreadRecordUpsertRequest requestWithVisibility(Boolean isPublic) {
		return new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "소금빵", null, null, 0.5, 0.5, null, null)),
			isPublic);
	}

	@Test
	@DisplayName("create: 사진 없이 가게와 빵 이름만으로 저장한다 — 태그는 사진도 좌표도 비운다")
	void create_savesPhotolessRecordWithUnpinnedTag() {
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");

		commandService.create(7L, photolessRequest(null));

		verify(tagRepository).saveAll(argThat(tags -> {
			BreadRecordTag tag = firstTag(tags);
			return tag.getName().equals("소금빵") && tag.getPhoto() == null
				&& tag.getPositionX() == null && tag.getPositionY() == null;
		}));
	}

	@Test
	@DisplayName("create: 사진 순서 없이 좌표만 보낸 태그는 거절한다")
	void create_rejectsUnpinnedTagWithPosition() {
		Store store = store(10L);
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		BreadRecordUpsertRequest request = new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7), List.of(),
			List.of(new BreadRecordTagRequest(null, "소금빵", null, null, 0.3, null, null, null)), null);

		assertThatThrownBy(() -> commandService.create(7L, request))
			.isInstanceOf(BreadLogException.class)
			.extracting("errorCode").isEqualTo(BreadLogErrorCode.INVALID_TAG_POSITION);
	}

	@Test
	@DisplayName("create: 보상 계산에 사진 수와 저장 전·후의 빵탑 높이를 넘기고, 받은 보상을 응답에 싣는다")
	void create_passesHeightsToRewards() {
		givenCreatableRecord();
		given(memberWorldRepository.findMaxRecordCount(7L)).willReturn(Optional.of(4));
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(7L)).willReturn(4L, 5L);
		List<RewardItemResponse> rewards = List.of(RewardItemResponse.bread(4, 5));
		given(recordRewardService.rewardCreated(any(Member.class), any(), eq(1), eq(4), eq(5))).willReturn(rewards);

		BreadRecordCreateResponse response = commandService.create(7L, requestWithTowerBread("소금빵"));

		assertThat(response.rewards()).isEqualTo(rewards);
	}

	@Test
	@DisplayName("create: 지웠다 다시 쓴 기록은 빵탑 높이가 오르지 않은 것으로 넘긴다")
	void create_keepsHeightWhenBelowStoredMax() {
		givenCreatableRecord();
		given(memberWorldRepository.findMaxRecordCount(7L)).willReturn(Optional.of(9));
		given(breadRecordRepository.countByMemberIdAndDeletedAtIsNull(7L)).willReturn(4L, 5L);

		commandService.create(7L, requestWithTowerBread("소금빵"));

		verify(recordRewardService).rewardCreated(any(Member.class), any(), eq(1), eq(9), eq(9));
	}

	@Test
	@DisplayName("create: 같은 requestId 가 다시 오면 새로 만들지 않고 처음 만든 기록을 보상 없이 돌려준다")
	void create_replaysSameRequest() {
		UUID requestId = UUID.fromString("7f1c9a52-0d43-4a0b-9d6e-0f2b6c1a8e11");
		Member member = member(7L);
		given(memberReader.getMember(7L)).willReturn(member);
		given(memberRepository.findActiveForUpdate(7L)).willReturn(Optional.of(member));
		BreadRecord created = record(11L, member);
		ReflectionTestUtils.setField(created, "createdAt", LocalDateTime.of(2026, 8, 4, 12, 0));
		given(breadRecordRepository.findByMemberIdAndClientRequestId(7L, requestId)).willReturn(Optional.of(created));

		BreadRecordCreateResponse response = commandService.create(7L, photolessRequest(requestId));

		assertThat(response).isEqualTo(
			new BreadRecordCreateResponse(11L, LocalDateTime.of(2026, 8, 4, 12, 0), List.of()));
		verify(breadRecordRepository, never()).save(any());
		org.mockito.Mockito.verifyNoInteractions(recordRewardService);
	}

	@Test
	@DisplayName("create: 처음 보는 requestId 면 회원 행을 잠그고 그 번호를 기록에 남긴다")
	void create_storesRequestId() {
		UUID requestId = UUID.fromString("7f1c9a52-0d43-4a0b-9d6e-0f2b6c1a8e11");
		givenCreatableRecord();
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(memberRepository.findActiveForUpdate(7L)).willReturn(Optional.of(member(7L)));
		given(breadRecordRepository.findByMemberIdAndClientRequestId(7L, requestId)).willReturn(Optional.empty());

		commandService.create(7L, photolessRequest(requestId));

		verify(breadRecordRepository).save(argThat(record -> requestId.equals(record.getClientRequestId())));
	}

	@Test
	@DisplayName("replace: 사진을 붙인 수정은 사진 보너스를 판정해 응답에 싣는다")
	void replace_returnsPhotoBonus() {
		BreadRecord record = givenReplaceableRecord(false);
		List<RewardItemResponse> rewards = List.of(RewardItemResponse.bread(1, 2));
		given(recordRewardService.rewardPhotosAttached(any(Member.class), eq(11L), any(), eq(1))).willReturn(rewards);

		BreadRecordUpdateResponse response = commandService.replace(11L, 7L, requestWithVisibility(null));

		assertThat(record.getId()).isEqualTo(11L);
		assertThat(response.rewards()).isEqualTo(rewards);
	}

	@Test
	@DisplayName("replace: 이 수정으로 채워진 빵투어 사진 완주 보상을 사진 보너스 뒤에 이어 싣는다")
	void replace_appendsTourRewards() {
		givenReplaceableRecord(false);
		given(recordRewardService.rewardPhotosAttached(any(Member.class), eq(11L), any(), eq(1)))
			.willReturn(List.of(RewardItemResponse.bread(1, 2)));
		given(tourRewardService.rewardPhotoCompletions(any(Member.class), any()))
			.willReturn(List.of(RewardItemResponse.tourStep(812L, "PHOTO_COMPLETE")));

		BreadRecordUpdateResponse response = commandService.replace(11L, 7L, requestWithVisibility(null));

		assertThat(response.rewards()).containsExactly(
			RewardItemResponse.bread(1, 2), RewardItemResponse.tourStep(812L, "PHOTO_COMPLETE"));
	}

	@Test
	@DisplayName("createTourQuickRecord: 가게만 있는 비공개 기록을 만들고, 보상 키는 (빵투어, 가게)이며 급부상 신호를 내지 않는다")
	void createTourQuickRecord_lightRecord() {
		Store store = store(10L);
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(breadRecordRepository.save(any())).willAnswer(invocation -> {
			BreadRecord record = invocation.getArgument(0);
			ReflectionTestUtils.setField(record, "id", 31L);
			return record;
		});
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
		List<RewardItemResponse> rewards = List.of(RewardItemResponse.bread(0, 1));
		given(recordRewardService.rewardTourRecordCreated(any(Member.class), eq(31L), eq(812L), eq(10L),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).willReturn(rewards);

		BreadRecordCreateResponse response = commandService.createTourQuickRecord(7L, 812L, store, null);

		assertThat(response.recordId()).isEqualTo(31L);
		assertThat(response.rewards()).isEqualTo(rewards);
		verify(breadRecordRepository).save(argThat(record -> !record.isPublic() && record.getStore() == store));
		org.mockito.Mockito.verifyNoInteractions(eventPublisher);
		verify(recordRewardService, never()).rewardCreated(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
	}

	private BreadRecordUpsertRequest photolessRequest(UUID requestId) {
		return new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7), List.of(),
			List.of(new BreadRecordTagRequest(null, "소금빵", null, null, null, null, null, null)), null, requestId);
	}

	private void givenCreatableRecord() {
		Store store = store(10L);
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(regionResolver.resolve(store, "client_region")).willReturn("서울_성동구_성수동");
		given(breadRecordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	private BreadRecordUpsertRequest requestWithTowerBread(String towerBread) {
		return new BreadRecordUpsertRequest(
			10L,
			"클라이언트 가게명",
			"client_region",
			"맛있었다",
			"우유식빵",
			null,
			towerBread,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "우유식빵", null, null, 0.31, 0.42, null, null)),
			null);
	}

	private BreadRecordUpsertRequest requestWithTagMenuId(Long menuId) {
		return requestWithTagMenus(menuId, null);
	}

	private BreadRecordUpsertRequest requestWithTagMenus(Long menuId, Long suggestedMenuId) {
		return new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "소금빵", menuId, suggestedMenuId, 0.5, 0.5, null, null)), null);
	}

	private static BreadRecordTag firstTag(Iterable<BreadRecordTag> tags) {
		return StreamSupport.stream(tags.spliterator(), false).toList().get(0);
	}

	private Menu menu(Long id, String name) {
		Menu menu = Menu.builder().name(name).build();
		ReflectionTestUtils.setField(menu, "id", id);
		return menu;
	}

	private Member member(Long id) {
		Member member = Member.builder().nickname("테스터").build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Store store(Long id) {
		Store store = Store.builder().name("밀도 성수").address("서울 성동구").build();
		ReflectionTestUtils.setField(store, "id", id);
		return store;
	}

	private BreadRecord record(Long id, Member member) {
		BreadRecord record = BreadRecord.builder()
			.member(member)
			.storeName("밀도 성수")
			.eatenAt(LocalDateTime.now())
			.build();
		ReflectionTestUtils.setField(record, "id", id);
		return record;
	}

	@Test
	@DisplayName("create: 태그에 실린 빵 평가(또 먹을래요·식감·맛·메모)를 저장한다")
	void create_savesTagRating() {
		givenCreatableRecord();
		BreadTagRatingRequest rating = new BreadTagRatingRequest(
			true, List.of(TextureChip.겉바속촉, TextureChip.쫄깃), List.of(TasteChip.짭짤), " 겉은 바삭 ");

		commandService.create(7L, requestWithRating(rating, null));

		verify(tagRepository).saveAll(argThat(tags -> {
			BreadRecordTag tag = firstTag(tags);
			return Boolean.TRUE.equals(tag.getAgain())
				&& tag.textures().equals(List.of(TextureChip.겉바속촉, TextureChip.쫄깃))
				&& tag.tastes().equals(List.of(TasteChip.짭짤))
				&& "겉은 바삭".equals(tag.getRatingMemo());
		}));
	}

	@Test
	@DisplayName("create: 하트(lovedBreads)로 고른 빵은 또 먹을래요 on 으로 저장한다 — 구버전 앱")
	void create_lovedBreadMarksAgain() {
		givenCreatableRecord();

		commandService.create(7L, requestWithRating(null, List.of("우유식빵")));

		verify(tagRepository).saveAll(argThat(tags -> Boolean.TRUE.equals(firstTag(tags).getAgain())));
	}

	@Test
	@DisplayName("create: 평가가 또 먹을래요를 off 로 명시하면 하트가 있어도 그대로 둔다")
	void create_explicitRatingBeatsLovedBread() {
		givenCreatableRecord();
		BreadTagRatingRequest rating = new BreadTagRatingRequest(false, null, null, null);

		commandService.create(7L, requestWithRating(rating, List.of("우유식빵")));

		verify(tagRepository).saveAll(argThat(tags -> Boolean.FALSE.equals(firstTag(tags).getAgain())));
	}

	@Test
	@DisplayName("replace: 요청에 평가가 없으면 이름 같은 이전 태그의 평가를 이어간다 — 구버전 앱의 수정")
	void replace_withoutRating_carriesOverPreviousRating() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		BreadRecordTag previous = BreadRecordTag.builder()
			.record(record).name("우유식빵").breadCategory("식빵").positionX(0.1).positionY(0.1).build();
		previous.rate(true, List.of(TextureChip.촉촉), List.of(TasteChip.고소), "부드러워");
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));
		given(tagRepository.findAllWithChipsByRecordId(11L)).willReturn(List.of(previous));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(categoryClassifier.classify("우유식빵")).willReturn("식빵");

		commandService.replace(11L, 7L, requestWithRating(null, null));

		verify(tagRepository).saveAll(argThat(tags -> {
			BreadRecordTag tag = firstTag(tags);
			return Boolean.TRUE.equals(tag.getAgain())
				&& tag.textures().equals(List.of(TextureChip.촉촉))
				&& "부드러워".equals(tag.getRatingMemo());
		}));
	}

	@Test
	@DisplayName("replace: 빈 평가를 보내면 이전 평가를 지운다")
	void replace_withEmptyRating_clearsPrevious() {
		Member member = member(7L);
		BreadRecord record = record(11L, member);
		BreadRecordTag previous = BreadRecordTag.builder()
			.record(record).name("우유식빵").breadCategory("식빵").positionX(0.1).positionY(0.1).build();
		previous.rate(true, List.of(TextureChip.촉촉), null, null);
		given(memberReader.getMember(7L)).willReturn(member);
		given(breadRecordRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));
		given(tagRepository.findAllWithChipsByRecordId(11L)).willReturn(List.of(previous));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(categoryClassifier.classify("우유식빵")).willReturn("식빵");

		commandService.replace(11L, 7L, requestWithRating(new BreadTagRatingRequest(null, null, null, null), null));

		verify(tagRepository).saveAll(argThat(tags -> !firstTag(tags).hasRating()));
	}

	private BreadRecordUpsertRequest requestWithRating(BreadTagRatingRequest rating, List<String> lovedBreads) {
		return new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, lovedBreads, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "우유식빵", null, null, 0.31, 0.42, null, rating)), null);
	}
}
