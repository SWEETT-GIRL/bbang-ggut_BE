package sweetgirl.croissant.breadranking.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.breadranking.dto.response.MyBreadCategoryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankEntryResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingChangeResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRankingResponse;
import sweetgirl.croissant.breadranking.dto.response.MyBreadRatingResponse;
import sweetgirl.croissant.breadranking.dto.response.StoreSummaryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.entity.BreadRankEntry;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategory;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategoryId;
import sweetgirl.croissant.breadranking.enums.RankMode;
import sweetgirl.croissant.breadranking.exception.BreadRankingException;
import sweetgirl.croissant.breadranking.repository.BreadRankEntryRepository;
import sweetgirl.croissant.breadranking.repository.MemberBreadCategoryRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 내 빵 순위 쓰기 — 탭 추가·삭제, 가게 담기·빼기, 등수 정하기, 그 가게의 내 평가 고치기.
 *
 * <p>등수는 이어서 센다(1, 2, 2, 3). 어떤 변경 뒤에도 등수별 묶음을 1부터 다시 번호 매겨 빈 번호가 없다.
 * 등수 없는 줄은 건드리지 않는다. 1등이 곧 한 표라 투표 API 는 따로 없고, 1등이 바뀌면 응답에 이전·새 1등을 담는다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class MyBreadRankingCommandService {

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	private final MemberReader memberReader;
	private final BreadCategoryQueryService categoryQueryService;
	private final MyBreadRankingQueryService rankingQueryService;
	private final MemberBreadCategoryRepository memberCategoryRepository;
	private final BreadRankEntryRepository entryRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final BreadRecordTagRepository tagRepository;
	private final StoreRepository storeRepository;

	/** 내 탭에 추가 — 이미 있으면 그대로. 맨 뒤에 붙는다. */
	public List<MyBreadCategoryResponse> addCategory(Long memberId, Long categoryId) {
		memberReader.requireLoggedIn(memberId);
		ensureTab(memberId, categoryQueryService.getCategory(categoryId));
		return rankingQueryService.getMyCategories(memberId);
	}

	/** 내 탭에서 빼기 — 그 카테고리의 내 순위(표 포함)도 같이 지운다. 남은 탭은 순서를 다시 매긴다. */
	public List<MyBreadCategoryResponse> removeCategory(Long memberId, Long categoryId) {
		memberReader.requireLoggedIn(memberId);
		categoryQueryService.getCategory(categoryId);
		entryRepository.deleteAllByMemberIdAndCategoryId(memberId, categoryId);
		memberCategoryRepository.deleteById(new MemberBreadCategoryId(memberId, categoryId));
		memberCategoryRepository.flush();
		List<MemberBreadCategory> tabs = memberCategoryRepository.findAllWithCategoryByMemberId(memberId);
		for (int index = 0; index < tabs.size(); index++) {
			tabs.get(index).moveTo(index);
		}
		return rankingQueryService.getMyCategories(memberId);
	}

	/** 담기 — 빵로그를 남긴 가게만, 등수는 비워 둔다. 탭에 없으면 탭도 추가. 이미 담긴 가게면 그대로. */
	public MyBreadRankingResponse addStore(Long memberId, Long categoryId, Long storeId) {
		memberReader.requireLoggedIn(memberId);
		BreadCategory category = categoryQueryService.getCategory(categoryId);
		Store store = storeRepository.findById(storeId)
			.filter(found -> found.getStatus() == StoreStatus.ACTIVE)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		if (!breadRecordRepository.existsByMemberIdAndStoreIdAndDeletedAtIsNull(memberId, storeId)) {
			throw new BreadRankingException(BreadRankingErrorCode.BREAD_RANK_NOT_EATEN);
		}
		// 더블탭·재시도가 와도 DB 가 한 번만 받는다 — 이미 담긴 가게면 그대로
		entryRepository.insertUnrankedIfAbsent(memberId, categoryId, store.getId());
		ensureTab(memberId, category);
		return rankingQueryService.toRanking(category,
			entryRepository.findAllWithStoreByMemberIdAndCategoryId(memberId, categoryId), memberId);
	}

	/**
	 * 등수 정하기. 등수별 묶음에서 이 가게를 뺀 뒤 — 마지막 다음이면 새 묶음, TIE 면 그 묶음에 합류, INSERT 면 그 자리에
	 * 새 묶음을 끼운다 — 그리고 1부터 다시 번호를 매긴다. 뒤로 밀린 줄은 번호만 바뀌고 정한 시각은 그대로다.
	 */
	public MyBreadRankingChangeResponse placeStore(Long memberId, Long categoryId, Long storeId, int rank,
			RankMode mode) {
		memberReader.requireLoggedIn(memberId);
		BreadCategory category = categoryQueryService.getCategory(categoryId);
		List<BreadRankEntry> entries = entryRepository.findAllWithStoreByMemberIdAndCategoryId(memberId, categoryId);
		BreadRankEntry target = find(entries, storeId);
		List<BreadRankEntry> previousTop = top(entries);
		List<List<BreadRankEntry>> groups = groups(entries, target);
		if (rank > groups.size() + 1) {
			throw new BreadRankingException(BreadRankingErrorCode.BREAD_RANK_INVALID_RANK);
		}
		if (rank == groups.size() + 1) {
			groups.add(new ArrayList<>(List.of(target)));
		} else if (mode == RankMode.TIE) {
			groups.get(rank - 1).add(target);
		} else {
			groups.add(rank - 1, new ArrayList<>(List.of(target)));
		}
		target.place(rank, LocalDateTime.now(ZONE));
		renumber(groups);
		return change(category, entries, previousTop, memberId);
	}

	/** 빼기 — 등수가 있던 줄이면 남은 줄을 다시 번호 매긴다. */
	public MyBreadRankingChangeResponse removeStore(Long memberId, Long categoryId, Long storeId) {
		memberReader.requireLoggedIn(memberId);
		BreadCategory category = categoryQueryService.getCategory(categoryId);
		List<BreadRankEntry> entries = entryRepository.findAllWithStoreByMemberIdAndCategoryId(memberId, categoryId);
		BreadRankEntry target = find(entries, storeId);
		List<BreadRankEntry> previousTop = top(entries);
		entryRepository.delete(target);
		List<BreadRankEntry> remaining = entries.stream().filter(entry -> entry != target).toList();
		renumber(groups(remaining, null));
		return change(category, remaining, previousTop, memberId);
	}

	/** 그 가게 × 빵 종류의 내 최신 태그 평가를 고친다(가게 시트). 태그가 없으면 404 — 평가는 빵로그에만 산다. */
	public MyBreadRatingResponse updateRating(Long memberId, Long categoryId, Long storeId,
			BreadTagRatingRequest rating) {
		memberReader.requireLoggedIn(memberId);
		BreadCategory category = categoryQueryService.getCategory(categoryId);
		find(entryRepository.findAllWithStoreByMemberIdAndCategoryId(memberId, categoryId), storeId);
		if (category.getMenuKind() == null) {
			throw new BreadRankingException(BreadRankingErrorCode.BREAD_RATING_NOT_FOUND);
		}
		BreadRecordTag tag = tagRepository.findLatestTagsByStores(memberId, List.of(storeId),
			category.getMenuKind().name(), category.getMenuKind()).stream()
			.findFirst()
			.orElseThrow(() -> new BreadRankingException(BreadRankingErrorCode.BREAD_RATING_NOT_FOUND));
		String memo = rating.memo() == null || rating.memo().isBlank() ? null : rating.memo().strip();
		tag.rate(rating.again(), rating.textures(), rating.tastes(), memo);
		return MyBreadRatingResponse.from(tag);
	}

	private void ensureTab(Long memberId, BreadCategory category) {
		memberCategoryRepository.appendIfAbsent(memberId, category.getId());
	}

	private static BreadRankEntry find(List<BreadRankEntry> entries, Long storeId) {
		return entries.stream()
			.filter(entry -> entry.getStore().getId().equals(storeId))
			.findFirst()
			.orElseThrow(() -> new BreadRankingException(BreadRankingErrorCode.BREAD_RANK_ENTRY_NOT_FOUND));
	}

	/** 지금 1등(공동 포함) — 가게 id 순. */
	static List<BreadRankEntry> top(List<BreadRankEntry> entries) {
		return entries.stream()
			.filter(BreadRankEntry::isTop)
			.sorted(Comparator.comparing(entry -> entry.getStore().getId()))
			.toList();
	}

	/** 등수 있는 줄을(excluded 는 빼고) 등수별 묶음으로 — 1등 묶음, 2등 묶음… 순. */
	static List<List<BreadRankEntry>> groups(List<BreadRankEntry> entries, BreadRankEntry excluded) {
		List<BreadRankEntry> ranked = entries.stream()
			.filter(BreadRankEntry::isRanked)
			.filter(entry -> entry != excluded)
			.sorted(Comparator.comparing(BreadRankEntry::getRank).thenComparing(BreadRankEntry::getId,
				Comparator.nullsLast(Comparator.naturalOrder())))
			.toList();
		List<List<BreadRankEntry>> groups = new ArrayList<>();
		Integer current = null;
		for (BreadRankEntry entry : ranked) {
			if (!entry.getRank().equals(current)) {
				groups.add(new ArrayList<>());
				current = entry.getRank();
			}
			groups.get(groups.size() - 1).add(entry);
		}
		return groups;
	}

	/** 묶음 순서대로 1부터 — 번호가 달라진 줄만 고친다(정한 시각은 그대로). */
	static void renumber(List<List<BreadRankEntry>> groups) {
		for (int index = 0; index < groups.size(); index++) {
			int rank = index + 1;
			for (BreadRankEntry entry : groups.get(index)) {
				if (entry.getRank() == null || entry.getRank() != rank) {
					entry.renumber(rank);
				}
			}
		}
	}

	private MyBreadRankingChangeResponse change(
			BreadCategory category, List<BreadRankEntry> entries, List<BreadRankEntry> previousTop, Long memberId) {
		List<BreadRankEntry> currentTop = top(entries);
		MyBreadRankingResponse ranking = rankingQueryService.toRanking(category, entries, memberId);
		Map<Long, StoreSummaryResponse> summaryByStore = Stream
			.concat(ranking.ranked().stream(), ranking.unranked().stream())
			.map(MyBreadRankEntryResponse::store)
			.collect(Collectors.toMap(StoreSummaryResponse::storeId, Function.identity(), (first, second) -> first));
		Function<BreadRankEntry, StoreSummaryResponse> summary = entry -> summaryByStore.getOrDefault(
			entry.getStore().getId(), StoreSummaryResponse.of(entry.getStore(), null));
		boolean topChanged = !storeIds(previousTop).equals(storeIds(currentTop));
		return new MyBreadRankingChangeResponse(
			previousTop.stream().map(summary).toList(),
			currentTop.stream().map(summary).toList(),
			topChanged,
			ranking);
	}

	private static List<Long> storeIds(List<BreadRankEntry> entries) {
		return entries.stream().map(entry -> entry.getStore().getId()).toList();
	}
}
