package sweetgirl.croissant.breadlog.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.dto.response.PopularBreadResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.config.cache.CacheConfig;

/**
 * 요즘 빵순이들이 많이 먹은 빵 — 최근 30 일 기록의 빵 태그를 분류별로 센다.
 * 첫 기록 화면의 추천 칩과, 메뉴가 없는 빵집에서 "자주 기록되는 빵"으로 보여 준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PopularBreadQueryService {

	/** 이 기간의 기록을 센다. 7 일은 기록이 적은 주에 순위가 들쑥날쑥해 한 달로 본다. */
	static final int WINDOW_DAYS = 30;
	static final int MAX_SIZE = 20;

	private final BreadRecordTagRepository tagRepository;

	@Cacheable(cacheNames = CacheConfig.POPULAR_BREADS, key = "#size")
	public List<PopularBreadResponse> getPopularBreads(int size) {
		int limit = Math.max(1, Math.min(size, MAX_SIZE));
		LocalDateTime since = LocalDateTime.now(RankingRegionService.ZONE).minusDays(WINDOW_DAYS);
		return tagRepository.findPopularCategories(since, BreadRecordTag.fallbackCategories(), PageRequest.of(0, limit))
			.stream()
			.map(row -> new PopularBreadResponse(row.getName(), row.getTagCount()))
			.toList();
	}
}
