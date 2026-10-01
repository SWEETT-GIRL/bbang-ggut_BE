package sweetgirl.croissant.breadranking.service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankEntryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.entity.BreadRankEntry;
import sweetgirl.croissant.breadranking.repository.BreadRankEntryRepository;
import sweetgirl.croissant.breadranking.repository.MemberBreadCategoryRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.service.StoreThumbnailReader;

/** 내 빵 순위 조회 — 탭, 한 카테고리의 내 줄, 담을 후보. 로그인한 회원만. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyBreadRankingQueryService {

	private static final Comparator<BreadRankEntry> RANKED_ORDER = Comparator
		.comparing(BreadRankEntry::getRank)
		.thenComparing(BreadRankEntry::getRankedAt, Comparator.nullsLast(Comparator.naturalOrder()))
		.thenComparing(BreadRankEntry::getId);
	private static final Comparator<BreadRankEntry> UNRANKED_ORDER = Comparator
		.comparing(BreadRankEntry::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(BreadRankEntry::getId, Comparator.reverseOrder());

	private final MemberReader memberReader;
	private final BreadCategoryQueryService categoryQueryService;
	private final MemberBreadCategoryRepository memberCategoryRepository;
	private final BreadRankEntryRepository entryRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordTagRepository tagRepository;
	private final StoreThumbnailReader thumbnailReader;

	/** 내 탭(순서) + 탭마다 내 1등. */
	public List<MyBreadCategoryResponse> getMyCategories(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		Map<Long, List<BreadRankEntry>> topByCategory = entryRepository.findTopEntriesWithStoreByMemberId(memberId)
			.stream()
			.collect(Collectors.groupingBy(entry -> entry.getCategory().getId()));
		Map<Long, String> thumbnails = thumbnails(topByCategory.values().stream()
			.flatMap(List::stream)
			.map(entry -> entry.getStore().getId())
			.toList());
		return memberCategoryRepository.findAllWithCategoryByMemberId(memberId).stream()
			.map(tab -> new MyBreadCategoryResponse(
				BreadCategoryResponse.of(tab.getCategory(), 0),
				tab.getSortOrder(),
				topByCategory.getOrDefault(tab.getCategory().getId(), List.of()).stream()
					.map(entry -> StoreSummaryResponse.of(entry.getStore(), thumbnails.get(entry.getStore().getId())))
					.toList()))
			.toList();
	}

	/** 한 카테고리의 내 줄 전부 — 등수 순(공동 포함)과 아직 안 정한 것, 줄마다 그 가게의 내 최신 평가. */
	public MyBreadRankingResponse getMyRanking(Long memberId, Long categoryId) {
		memberReader.requireLoggedIn(memberId);
		BreadCategory category = categoryQueryService.getCategory(categoryId);
		return toRanking(category, entryRepository.findAllWithStoreByMemberIdAndCategoryId(memberId, categoryId),
			memberId);
	}

	/** 담을 후보 — 빵로그를 남긴 가게 중 아직 이 카테고리에 안 담은 것. query 는 가게 이름 부분 일치. */
	public List<StoreSummaryResponse> getCandidates(Long memberId, Long categoryId, String query) {
		memberReader.requireLoggedIn(memberId);
		categoryQueryService.getCategory(categoryId);
		Set<Long> added = entryRepository.findAllWithStoreByMemberIdAndCategoryId(memberId, categoryId).stream()
			.map(entry -> entry.getStore().getId())
			.collect(Collectors.toSet());
		List<Store> stores = breadRecordRepository.findEatenStores(memberId, query == null ? "" : query.strip())
			.stream()
			.filter(store -> !added.contains(store.getId()))
			.toList();
		Map<Long, String> thumbnails = thumbnails(stores.stream().map(Store::getId).toList());
		return stores.stream()
			.map(store -> StoreSummaryResponse.of(store, thumbnails.get(store.getId())))
			.toList();
	}

	/** 명령 서비스가 바꾼 뒤 같은 모양으로 돌려주려고 쓴다. */
	MyBreadRankingResponse toRanking(BreadCategory category, List<BreadRankEntry> entries, Long memberId) {
		Map<Long, MyBreadRatingResponse> ratingByStore = latestRatingByStore(memberId, category,
			entries.stream().map(entry -> entry.getStore().getId()).toList());
		Map<Long, String> thumbnails = thumbnails(entries.stream().map(entry -> entry.getStore().getId()).toList());
		Function<BreadRankEntry, MyBreadRankEntryResponse> toResponse = entry -> MyBreadRankEntryResponse.of(
			entry, thumbnails.get(entry.getStore().getId()), ratingByStore.get(entry.getStore().getId()));
		return new MyBreadRankingResponse(
			BreadCategoryResponse.of(category, 0),
			entries.stream().filter(BreadRankEntry::isRanked).sorted(RANKED_ORDER).map(toResponse).toList(),
			entries.stream().filter(entry -> !entry.isRanked()).sorted(UNRANKED_ORDER).map(toResponse).toList());
	}

	/**
	 * 가게마다 그 빵 종류로 단 내 최신 태그. 사용자 생성 카테고리(MenuKind 없음)는 태그와 이을 길이 없어 비어 있다.
	 * 쿼리 한 번 — 줄 수만큼 묻지 않는다.
	 */
	Map<Long, MyBreadRatingResponse> latestRatingByStore(Long memberId, BreadCategory category, List<Long> storeIds) {
		if (category.getMenuKind() == null || storeIds.isEmpty()) {
			return Map.of();
		}
		return tagRepository.findLatestTagsByStores(memberId, storeIds, category.getMenuKind().name(),
			category.getMenuKind()).stream()
			.collect(Collectors.toMap(
				tag -> tag.getRecord().getStore().getId(),
				MyBreadRatingResponse::from,
				(latest, older) -> latest));
	}

	private Map<Long, String> thumbnails(List<Long> storeIds) {
		return thumbnailReader.thumbnailsOf(storeIds);
	}
}
