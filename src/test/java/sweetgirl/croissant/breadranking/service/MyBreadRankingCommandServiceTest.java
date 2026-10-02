package sweetgirl.croissant.breadranking.service;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingChangeResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.entity.BreadRankEntry;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategory;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategoryId;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.enums.RankMode;
import sweetgirl.croissant.breadranking.exception.BreadRankingException;
import sweetgirl.croissant.breadranking.repository.BreadRankEntryRepository;
import sweetgirl.croissant.breadranking.repository.MemberBreadCategoryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class MyBreadRankingCommandServiceTest {

	private static final Long MEMBER_ID = 7L;
	private static final Long CATEGORY_ID = 1L;

	@Mock
	private MemberReader memberReader;
	@Mock
	private BreadCategoryQueryService categoryQueryService;
	@Mock
	private MyBreadRankingQueryService rankingQueryService;
	@Mock
	private MemberBreadCategoryRepository memberCategoryRepository;
	@Mock
	private BreadRankEntryRepository entryRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordTagRepository tagRepository;
	@Mock
	private StoreRepository storeRepository;

	@InjectMocks
	private MyBreadRankingCommandService service;

	private BreadCategory category;
	private BreadRankEntry first;
	private BreadRankEntry second;
	private BreadRankEntry third;
	private BreadRankEntry unranked;

	@BeforeEach
	void setUp() {
		category = category(CATEGORY_ID, MenuKind.소금빵);
		first = entry(1L, store(1L), 1);
		second = entry(2L, store(2L), 2);
		third = entry(3L, store(3L), 3);
		unranked = entry(4L, store(4L), null);
	}

	@Test
	@DisplayName("담기: 빵로그를 남기지 않은 가게는 400 — 저장하지 않는다")
	void addStore_rejectsNotEaten() {
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(storeRepository.findById(10L)).willReturn(Optional.of(store(10L)));
		given(breadRecordRepository.existsByMemberIdAndStoreIdAndDeletedAtIsNull(MEMBER_ID, 10L)).willReturn(false);

		assertThatThrownBy(() -> service.addStore(MEMBER_ID, CATEGORY_ID, 10L))
			.isInstanceOf(BreadRankingException.class)
			.extracting("errorCode")
			.isEqualTo(BreadRankingErrorCode.BREAD_RANK_NOT_EATEN);
		verify(entryRepository, never()).insertUnrankedIfAbsent(any(), any(), any());
	}

	@Test
	@DisplayName("담기: 등수 없이 DB 에 한 번만 넣고(더블탭이어도), 탭에 없으면 맨 뒤 탭으로 추가한다")
	void addStore_savesUnrankedAndAddsTab() {
		Store store = store(10L);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(storeRepository.findById(10L)).willReturn(Optional.of(store));
		given(breadRecordRepository.existsByMemberIdAndStoreIdAndDeletedAtIsNull(MEMBER_ID, 10L)).willReturn(true);
		given(rankingQueryService.toRanking(any(), any(), any())).willReturn(emptyRanking());

		service.addStore(MEMBER_ID, CATEGORY_ID, 10L);

		verify(entryRepository).insertUnrankedIfAbsent(MEMBER_ID, CATEGORY_ID, 10L);
		verify(memberCategoryRepository).appendIfAbsent(MEMBER_ID, CATEGORY_ID);
		verify(entryRepository).findAllWithStoreByMemberIdAndCategoryId(MEMBER_ID, CATEGORY_ID);
	}

	@Test
	@DisplayName("등수 INSERT: 그 자리에 끼우고 뒤를 민다 — 1, 2, 3 에 2번째로 끼우면 1, 2, 3, 4")
	void placeStore_insertShiftsFollowing() {
		givenEntries(first, second, third, unranked);

		MyBreadRankingChangeResponse response = service.placeStore(MEMBER_ID, CATEGORY_ID, 4L, 2, RankMode.INSERT);

		assertThat(unranked.getRank()).isEqualTo(2);
		assertThat(unranked.getRankedAt()).isNotNull();
		assertThat(first.getRank()).isEqualTo(1);
		assertThat(second.getRank()).isEqualTo(3);
		assertThat(third.getRank()).isEqualTo(4);
		assertThat(response.topChanged()).isFalse();
	}

	@Test
	@DisplayName("등수 TIE: 같이 선다 — 공동 1등이 되면 1등이 바뀐 것이고 이전·새 1등을 준다")
	void placeStore_tieJoinsRank() {
		givenEntries(first, second, third, unranked);

		MyBreadRankingChangeResponse response = service.placeStore(MEMBER_ID, CATEGORY_ID, 4L, 1, RankMode.TIE);

		assertThat(unranked.getRank()).isEqualTo(1);
		assertThat(first.getRank()).isEqualTo(1);
		assertThat(second.getRank()).isEqualTo(2);
		assertThat(response.topChanged()).isTrue();
		assertThat(response.previousTop()).extracting(StoreSummaryResponse::storeId).containsExactly(1L);
		assertThat(response.currentTop()).extracting(StoreSummaryResponse::storeId).containsExactly(1L, 4L);
	}

	@Test
	@DisplayName("등수: 마지막 다음 등수는 그냥 들어가고, 그보다 크면 400")
	void placeStore_appendOrReject() {
		givenEntries(first, second, third, unranked);

		service.placeStore(MEMBER_ID, CATEGORY_ID, 4L, 4, RankMode.INSERT);
		assertThat(unranked.getRank()).isEqualTo(4);

		assertThatThrownBy(() -> service.placeStore(MEMBER_ID, CATEGORY_ID, 4L, 6, RankMode.TIE))
			.isInstanceOf(BreadRankingException.class)
			.extracting("errorCode")
			.isEqualTo(BreadRankingErrorCode.BREAD_RANK_INVALID_RANK);
	}

	@Test
	@DisplayName("등수: 이미 등수 있는 줄을 위로 옮기면 빈 번호 없이 다시 매긴다 — 3등을 1등으로 끼우면 1, 2, 3")
	void placeStore_movesExistingUp() {
		givenEntries(first, second, third);

		MyBreadRankingChangeResponse response = service.placeStore(MEMBER_ID, CATEGORY_ID, 3L, 1, RankMode.INSERT);

		assertThat(third.getRank()).isEqualTo(1);
		assertThat(first.getRank()).isEqualTo(2);
		assertThat(second.getRank()).isEqualTo(3);
		assertThat(response.topChanged()).isTrue();
		assertThat(response.currentTop()).extracting(StoreSummaryResponse::storeId).containsExactly(3L);
	}

	@Test
	@DisplayName("등수: 내 순위에 없는 가게는 404")
	void placeStore_unknownStore() {
		givenEntriesWithoutRanking(first);

		assertThatThrownBy(() -> service.placeStore(MEMBER_ID, CATEGORY_ID, 99L, 1, RankMode.TIE))
			.isInstanceOf(BreadRankingException.class)
			.extracting("errorCode")
			.isEqualTo(BreadRankingErrorCode.BREAD_RANK_ENTRY_NOT_FOUND);
	}

	@Test
	@DisplayName("빼기: 1등을 빼면 남은 줄이 1부터 다시 — 1등이 바뀐다")
	void removeStore_renumbersRemaining() {
		givenEntries(first, second, third, unranked);

		MyBreadRankingChangeResponse response = service.removeStore(MEMBER_ID, CATEGORY_ID, 1L);

		verify(entryRepository).delete(first);
		assertThat(second.getRank()).isEqualTo(1);
		assertThat(third.getRank()).isEqualTo(2);
		assertThat(unranked.getRank()).isNull();
		assertThat(response.topChanged()).isTrue();
		assertThat(response.previousTop()).extracting(StoreSummaryResponse::storeId).containsExactly(1L);
		assertThat(response.currentTop()).extracting(StoreSummaryResponse::storeId).containsExactly(2L);
	}

	@Test
	@DisplayName("평가 고치기: 그 가게 × 빵 종류의 내 최신 태그에 평가를 넣는다")
	void updateRating_updatesLatestTag() {
		Store store = first.getStore();
		BreadRecordTag tag = tag(store);
		givenEntriesWithoutRanking(first);
		given(tagRepository.findLatestTagsByStores(MEMBER_ID, List.of(1L), "소금빵", MenuKind.소금빵))
			.willReturn(List.of(tag));

		MyBreadRatingResponse response = service.updateRating(MEMBER_ID, CATEGORY_ID, 1L,
			new BreadTagRatingRequest(true, List.of(TextureChip.겉바속촉), List.of(TasteChip.짭짤), " 짭짤해 "));

		assertThat(response.again()).isTrue();
		assertThat(response.textures()).containsExactly(TextureChip.겉바속촉);
		assertThat(response.memo()).isEqualTo("짭짤해");
		assertThat(tag.getRatingMemo()).isEqualTo("짭짤해");
	}

	@Test
	@DisplayName("평가 고치기: 그 빵을 태그한 빵로그가 없으면 404")
	void updateRating_noTag() {
		givenEntriesWithoutRanking(first);
		given(tagRepository.findLatestTagsByStores(eq(MEMBER_ID), eq(List.of(1L)), eq("소금빵"), eq(MenuKind.소금빵)))
			.willReturn(List.of());

		assertThatThrownBy(() -> service.updateRating(MEMBER_ID, CATEGORY_ID, 1L,
			new BreadTagRatingRequest(true, null, null, null)))
			.isInstanceOf(BreadRankingException.class)
			.extracting("errorCode")
			.isEqualTo(BreadRankingErrorCode.BREAD_RATING_NOT_FOUND);
	}

	@Test
	@DisplayName("탭 빼기: 그 종류의 내 순위를 지우고 남은 탭 순서를 다시 매긴다")
	void removeCategory_deletesEntriesAndRenumbersTabs() {
		MemberBreadCategory remainingA = MemberBreadCategory.append(MEMBER_ID, category(2L, MenuKind.식빵), 1);
		MemberBreadCategory remainingB = MemberBreadCategory.append(MEMBER_ID, category(3L, MenuKind.베이글), 2);
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(memberCategoryRepository.findAllWithCategoryByMemberId(MEMBER_ID))
			.willReturn(List.of(remainingA, remainingB));
		given(rankingQueryService.getMyCategories(MEMBER_ID)).willReturn(List.of());

		service.removeCategory(MEMBER_ID, CATEGORY_ID);

		verify(entryRepository).deleteAllByMemberIdAndCategoryId(MEMBER_ID, CATEGORY_ID);
		verify(memberCategoryRepository).deleteById(new MemberBreadCategoryId(MEMBER_ID, CATEGORY_ID));
		assertThat(remainingA.getSortOrder()).isZero();
		assertThat(remainingB.getSortOrder()).isEqualTo(1);
	}

	private void givenEntries(BreadRankEntry... entries) {
		givenEntriesWithoutRanking(entries);
		given(rankingQueryService.toRanking(any(), any(), any())).willReturn(emptyRanking());
	}

	private void givenEntriesWithoutRanking(BreadRankEntry... entries) {
		given(categoryQueryService.getCategory(CATEGORY_ID)).willReturn(category);
		given(entryRepository.findAllWithStoreByMemberIdAndCategoryId(MEMBER_ID, CATEGORY_ID))
			.willReturn(List.of(entries));
	}

	private MyBreadRankingResponse emptyRanking() {
		return new MyBreadRankingResponse(BreadCategoryResponse.of(category, 0), List.of(), List.of());
	}

	private static BreadCategory category(Long id, MenuKind kind) {
		BreadCategory category = BreadCategory.custom(kind.name(), BreadGroup.빵, null);
		ReflectionTestUtils.setField(category, "id", id);
		ReflectionTestUtils.setField(category, "menuKind", kind);
		return category;
	}

	private static Store store(Long id) {
		Store store = Store.builder().name("가게 " + id).address("서울 성동구").build();
		ReflectionTestUtils.setField(store, "id", id);
		ReflectionTestUtils.setField(store, "status", StoreStatus.ACTIVE);
		return store;
	}

	private BreadRankEntry entry(Long id, Store store, Integer rank) {
		BreadRankEntry entry = BreadRankEntry.unranked(MEMBER_ID, category, store);
		ReflectionTestUtils.setField(entry, "id", id);
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
