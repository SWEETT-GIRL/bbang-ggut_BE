package sweetgirl.croissant.breadlog.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.config.BreadPhotoTagProperties;
import sweetgirl.croissant.breadlog.dto.analysis.BreadPhotoToAnalyze;
import sweetgirl.croissant.breadlog.dto.analysis.RawTagSuggestion;
import sweetgirl.croissant.breadlog.dto.analysis.StoreMenuOption;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse.PhotoSuggestions;
import sweetgirl.croissant.breadlog.dto.response.BreadPhotoAnalyzeResponse.TagSuggestion;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.menu.repository.MenuRepository;

/**
 * 빵로그 사진에서 빵을 찾아 그 가게 메뉴로 매핑한다. 확정은 하지 않는다 — 앱이 제안으로 띄우고 유저가 고른다.
 *
 * <p>일부러 트랜잭션을 걸지 않는다. 메뉴 조회는 짧은 트랜잭션이면 되고, 그 뒤 사진마다 수 초씩 걸리는
 * Gemini 호출 동안 DB 커넥션을 잡고 있을 이유가 없다({@code MenuClassificationService} 와 같은 이유).
 *
 * <p>사진 하나가 실패해도 전체를 실패시키지 않는다. 앱에게 실패와 "빵이 없다"는 똑같이 빈 목록이고,
 * 그래야 칩이 안 뜰 뿐 유저의 작성 흐름이 막히지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BreadPhotoTagSuggestionService {

	private final MenuRepository menuRepository;
	private final BreadPhotoTagSuggester breadPhotoTagSuggester;
	private final BreadPhotoTagRateLimiter breadPhotoTagRateLimiter;
	private final ExecutorService breadPhotoTagExecutor;
	private final BreadPhotoTagProperties properties;

	/**
	 * 앱이 부르는 자리. 기능이 꺼져 있으면 Gemini 를 부르지 않고 빈 결과를 돌려준다 —
	 * 앱은 "제안 없음"만 알면 되고 에러 처리를 따로 하지 않아도 된다.
	 */
	public BreadPhotoAnalyzeResponse analyzeForMember(
			Long memberId,
			Long storeId,
			List<BreadPhotoToAnalyze> photos) {
		// 기능을 켜는 날 갑자기 400 이 나지 않도록, 꺼져 있어도 요청 자체는 먼저 본다
		validateSizes(photos);
		if (!properties.isEnabled()) {
			return emptyFor(photos);
		}
		if (!breadPhotoTagRateLimiter.tryAcquire(memberId)) {
			throw new BreadLogException(BreadLogErrorCode.PHOTO_ANALYZE_RATE_LIMITED);
		}
		return analyze(storeId, photos);
	}

	private void validateSizes(List<BreadPhotoToAnalyze> photos) {
		long maxBytes = properties.getMaxPhotoSize().toBytes();
		boolean tooLarge = photos.stream().anyMatch(photo -> photo.image().length > maxBytes);
		if (tooLarge) {
			throw new BreadLogException(BreadLogErrorCode.PHOTO_TOO_LARGE);
		}
	}

	public BreadPhotoAnalyzeResponse analyze(Long storeId, List<BreadPhotoToAnalyze> photos) {
		List<StoreMenuOption> menus = loadMenuOptions(storeId);
		if (menus.isEmpty()) {
			// 고를 후보가 없으면 답도 없다. 부르지 않는 게 유일하게 맞는 동작이다
			return emptyFor(photos);
		}
		Map<Long, String> nameByMenuId = menus.stream()
			.collect(Collectors.toMap(StoreMenuOption::id, StoreMenuOption::name, (kept, ignored) -> kept));

		List<CompletableFuture<PhotoSuggestions>> pending = photos.stream()
			.map(photo -> suggestAsync(menus, photo)
				.thenApply(raw -> new PhotoSuggestions(photo.photoKey(), fold(raw, nameByMenuId))))
			.toList();

		return new BreadPhotoAnalyzeResponse(pending.stream().map(CompletableFuture::join).toList());
	}

	private CompletableFuture<List<RawTagSuggestion>> suggestAsync(
			List<StoreMenuOption> menus,
			BreadPhotoToAnalyze photo) {
		try {
			return CompletableFuture
				.supplyAsync(() -> breadPhotoTagSuggester.suggest(menus, photo), breadPhotoTagExecutor)
				.orTimeout(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS)
				.exceptionally(failed -> {
					Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
					log.info("사진 태그 제안 실패 — 이 사진만 비운다 (photoKey={}): {}", photo.photoKey(), cause.toString());
					return List.of();
				});
		} catch (RejectedExecutionException noRoom) {
			// 풀에 자리가 없으면 supplyAsync 가 그 자리에서 던진다 — 이 사진만 비우고 나머지는 계속 간다
			log.info("사진 태그 제안 자리 없음 — 이 사진만 비운다 (photoKey={})", photo.photoKey());
			return CompletableFuture.completedFuture(List.of());
		}
	}

	private List<StoreMenuOption> loadMenuOptions(Long storeId) {
		return menuRepository
			.findByStoreIdOrderByLikeCountDesc(storeId, PageRequest.of(0, properties.getMenuLimit()))
			.stream()
			.map(menu -> new StoreMenuOption(menu.getId(), menu.getName()))
			.toList();
	}

	/**
	 * 프롬프트를 믿지 않고 한 번 더 접는다 — 크루아상 하나를 다섯 줄로 답하는 걸 실측에서 봤다.
	 *
	 * <p>목록 밖 menuId 버리기 → 같은 메뉴는 상자가 큰 것 하나만 → 큰 것부터 상한까지.
	 */
	private List<TagSuggestion> fold(List<RawTagSuggestion> raw, Map<Long, String> nameByMenuId) {
		Map<Long, RawTagSuggestion> largestByMenuId = new LinkedHashMap<>();
		raw.stream()
			.filter(suggestion -> nameByMenuId.containsKey(suggestion.menuId()))
			.forEach(suggestion -> largestByMenuId.merge(
				suggestion.menuId(),
				suggestion,
				(kept, candidate) -> candidate.boxArea() > kept.boxArea() ? candidate : kept));

		return largestByMenuId.values().stream()
			.sorted(Comparator.comparingDouble(RawTagSuggestion::boxArea).reversed())
			.limit(properties.getMaxSuggestions())
			.map(suggestion -> new TagSuggestion(
				suggestion.menuId(),
				nameByMenuId.get(suggestion.menuId()),
				suggestion.x(),
				suggestion.y()))
			.toList();
	}

	private BreadPhotoAnalyzeResponse emptyFor(List<BreadPhotoToAnalyze> photos) {
		return new BreadPhotoAnalyzeResponse(photos.stream()
			.map(photo -> new PhotoSuggestions(photo.photoKey(), List.<TagSuggestion>of()))
			.toList());
	}
}
