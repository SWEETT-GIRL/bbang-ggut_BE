package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPhotoRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordPresignedUrlRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordTagRequest;
import sweetgirl.croissant.breadlog.dto.request.BreadRecordUpsertRequest;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordCreateResponse;
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
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class BreadRecordCommandServiceTest {

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
	}

	@Test
	@DisplayName("create: 최고였던 빵을 여러 개 저장하고 첫 값은 구버전 컬럼에도 비춘다")
	void create_multipleLovedBreads_saved() {
		Member member = member(7L);
		Store store = store(10L);
		BreadRecordUpsertRequest request = new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, "무시되는 단일값", List.of("소금빵", " 우유식빵 ", "소금빵"),
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(
				new BreadRecordPhotoRequest("a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(
				new BreadRecordTagRequest(0, "우유식빵", null, 0.31, 0.42, null),
				new BreadRecordTagRequest(0, "소금빵", null, 0.5, 0.5, null)));
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
			10L, "클라이언트 가게명", "client_region", null, null, List.of("우유식빵", "크루아상"),
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(
				new BreadRecordPhotoRequest("a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "우유식빵", null, 0.31, 0.42, null)));
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
		given(breadRecordRepository.findByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(record));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));
		given(photoRepository.findAllIncludingPendingByRecordId(11L)).willReturn(List.of(oldPhoto));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(categoryClassifier.classify("우유식빵")).willReturn("식빵");

		commandService.replace(11L, 7L, request);

		verify(s3Service, never()).deleteObject(awsKey);
		verify(photoRepository).saveAll(argThat(photos -> {
			List<BreadRecordPhoto> photoList = StreamSupport.stream(photos.spliterator(), false).toList();
			return photoList.size() == 1
				&& photoList.get(0).getProcessingStatus() == ImageStatus.DONE
				&& photoList.get(0).getResizedKey320().equals("resized/320/" + awsKey);
		}));
		verify(courseProgressService).refreshForMember(7L);
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
			null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest("a.jpg", "image/jpeg", awsKey, 0, null, null)),
			List.of(new BreadRecordTagRequest(tagPhotoSortOrder, "우유식빵", null, 0.31, 0.42, null)));
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
	@DisplayName("create: 가게를 안 고른 기록은 메뉴를 찾지 않는다")
	void create_withoutStore_skipsMenuLookup() {
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(regionResolver.resolve(null, "client_region")).willReturn(null);
		given(categoryClassifier.classify("소금빵")).willReturn("소금빵");
		given(breadRecordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));

		commandService.create(7L, new BreadRecordUpsertRequest(
			null, "직접 적은 가게", "client_region", null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "소금빵", 99L, 0.5, 0.5, null))));

		verify(tagRepository).saveAll(argThat(tags -> firstTag(tags).getMenu() == null));
		verify(menuRepository, never()).findByStoreIdAndDeletedAtIsNullAndIdIn(any(), any());
		verify(menuRepository, never()).findByStoreIdAndDeletedAtIsNullAndNormalizedNameIn(any(), any());
	}

	private void givenCreatableRecord() {
		Store store = store(10L);
		given(memberReader.getMember(7L)).willReturn(member(7L));
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(regionResolver.resolve(store, "client_region")).willReturn("서울_성동구_성수동");
		given(breadRecordRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
		given(photoRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
	}

	private BreadRecordUpsertRequest requestWithTagMenuId(Long menuId) {
		return new BreadRecordUpsertRequest(
			10L, "클라이언트 가게명", "client_region", null, null, null,
			LocalDateTime.of(2026, 8, 2, 14, 21, 7),
			List.of(new BreadRecordPhotoRequest(
				"a.jpg", "image/jpeg", "bread-records/members/7/images/a", 0, null, null)),
			List.of(new BreadRecordTagRequest(0, "소금빵", menuId, 0.5, 0.5, null)));
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
}
