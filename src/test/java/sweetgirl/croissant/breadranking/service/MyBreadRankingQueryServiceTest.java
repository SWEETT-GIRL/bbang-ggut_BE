package sweetgirl.croissant.breadranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.enums.TextureChip;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadranking.dto.response.MyBreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankEntryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.entity.BreadRankEntry;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadRankEntryRepository;
import sweetgirl.croissant.breadranking.repository.MemberBreadCategoryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.service.StoreThumbnailReader;

@ExtendWith(MockitoExtension.class)
class MyBreadRankingQueryServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long CATEGORY_ID = 1L;

	@Mock
	private MemberReader memberReader;
	@Mock
	private BreadCategoryQueryService categoryQueryService;
	@Mock
	private MemberBreadCategoryRepository memberCategoryRepository;
	@Mock
	private BreadRankEntryRepository entryRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordTagRepository tagRepository;
	@Mock
	private StoreThumbnailReader thumbnailReader;

	@InjectMocks
	private MyBreadRankingQueryService service;

	@Test
	@DisplayName("내 순위: 등수 순으로, 안 정한 가게는 따로, 줄마다 그 가게의 내 최신 평가")
	void getMyRanking_splitsRankedAndUnranked() {
		BreadCategory category = category(MenuKind.소금빵);
		Store storeA = store(1L);
		Store storeB = store(2L);
		Store storeC = store(3L);
		BreadRankEntry second = entry(category, storeA, 2);
		BreadRankEntry top = entry(category, storeB, 1);
		BreadRankEntry pending = entry(category, storeC, null);
		BreadRecordTag tag = tag(storeA);
		tag.rate(true, List.of(TextureChip.바삭), null, null);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(entryRepository.findAllWithStoreByMemberIdAndCategoryId(MEMBER_ID, CATEGORY_ID))
			.willReturn(List.of(second, top, pending));
		given(tagRepository.findLatestTagsByStores(MEMBER_ID, List.of(1L, 2L, 3L), "소금빵", MenuKind.소금빵))
			.willReturn(List.of(tag));

		MyBreadRankingResponse response = service.getMyRanking(MEMBER_ID, CATEGORY_ID);

		assertThat(response.ranked()).extracting(entry -> entry.store().storeId()).containsExactly(2L, 1L);
		assertThat(response.unranked()).extracting(entry -> entry.store().storeId()).containsExactly(3L);
		MyBreadRankEntryResponse rowA = response.ranked().get(1);
		assertThat(rowA.rating()).isNotNull();
		assertThat(rowA.rating().textures()).containsExactly(TextureChip.바삭);
		assertThat(response.ranked().get(0).rating()).isNull();
	}

	@Test
	@DisplayName("사용자 생성 카테고리(MenuKind 없음)는 태그와 이을 길이 없어 평가를 묻지 않는다")
	void getMyRanking_customCategoryHasNoRatings() {
		BreadCategory category = BreadCategory.custom("트러플 소금빵", BreadGroup.빵, 3L);
		ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(entryRepository.findAllWithStoreByMemberIdAndCategoryId(MEMBER_ID, CATEGORY_ID))
			.willReturn(List.of(entry(category, store(1L), 1)));

		MyBreadRankingResponse response = service.getMyRanking(MEMBER_ID, CATEGORY_ID);

		assertThat(response.ranked().get(0).rating()).isNull();
	}

	@Test
	@DisplayName("후보: 빵로그를 남긴 가게 중 이미 담은 가게는 뺀다")
	void getCandidates_excludesAdded() {
		BreadCategory category = category(MenuKind.소금빵);
		Store added = store(1L);
		Store candidate = store(2L);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(entryRepository.findAllWithStoreByMemberIdAndCategoryId(MEMBER_ID, CATEGORY_ID))
			.willReturn(List.of(entry(category, added, null)));
		given(breadRecordRepository.findEatenStores(MEMBER_ID, "")).willReturn(List.of(added, candidate));

		List<StoreSummaryResponse> candidates = service.getCandidates(MEMBER_ID, CATEGORY_ID, null);

		assertThat(candidates).extracting(StoreSummaryResponse::storeId).containsExactly(2L);
	}

	@Test
	@DisplayName("내 탭: 순서대로, 탭마다 내 1등 가게(공동이면 여러 개)")
	void getMyCategories_withTopStores() {
		BreadCategory category = category(MenuKind.소금빵);
		Store storeA = store(1L);
		Store storeB = store(2L);
		given(memberCategoryRepository.findAllWithCategoryByMemberId(MEMBER_ID))
			.willReturn(List.of(MemberBreadCategory.append(MEMBER_ID, category, 0)));
		given(entryRepository.findTopEntriesWithStoreByMemberId(MEMBER_ID))
			.willReturn(List.of(entry(category, storeA, 1), entry(category, storeB, 1)));

		List<MyBreadCategoryResponse> tabs = service.getMyCategories(MEMBER_ID);

		assertThat(tabs).hasSize(1);
		assertThat(tabs.get(0).category().name()).isEqualTo("소금빵");
		assertThat(tabs.get(0).topStores()).extracting(StoreSummaryResponse::storeId).containsExactly(1L, 2L);
	}

	private static BreadCategory category(MenuKind kind) {
		BreadCategory category = BreadCategory.custom(kind.name(), BreadGroup.빵, null);
		ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
		ReflectionTestUtils.setField(category, "menuKind", kind);
		return category;
	}

	private static Store store(Long id) {
		Store store = Store.builder().name("가게 " + id).address("서울 성동구").region1st("서울").region2nd("성동구").build();
		ReflectionTestUtils.setField(store, "id", id);
		return store;
	}

	private static BreadRankEntry entry(BreadCategory category, Store store, Integer rank) {
		BreadRankEntry entry = BreadRankEntry.unranked(MEMBER_ID, category, store);
		ReflectionTestUtils.setField(entry, "id", store.getId() * 10);
		if (rank != null) {
			entry.place(rank, LocalDateTime.of(2026, 10, 1, 10, 0));
		}
		return entry;
	}

	private static BreadRecordTag tag(Store store) {
		Member member = Member.builder().nickname("테스터").build();
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		BreadRecord record = BreadRecord.builder()
			.member(member).store(store).storeName(store.getName()).eatenAt(LocalDateTime.of(2026, 9, 30, 12, 0))
			.build();
		ReflectionTestUtils.setField(record, "id", 11L);
		BreadRecordTag tag = BreadRecordTag.builder()
			.record(record).name("소금빵").breadCategory("소금빵").positionX(0.5).positionY(0.5).build();
		ReflectionTestUtils.setField(tag, "id", 21L);
		return tag;
	}
}
