package sweetgirl.croissant.admin.bulkimport.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiter;
import sweetgirl.croissant.admin.bulkimport.enums.BulkSearchKeyword;
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class DongImporter {

	// 한 키워드당 최대 페이지 — 일반 시/군은 10이면 거의 cap 닿지 않고, 대도시는 일부 누락 가능.
	// cap 도달 로그가 잦은 dong 만 사후에 별도 재import 로 보강하는 운영 정책.
	private static final int MAX_PAGES = 10;
	// 적응형 조기 종료: page EARLY_STOP_CHECKPOINT_PAGE 까지 dedup 에 새로 추가된 결과가
	// EARLY_STOP_MIN_NEW_UNIQUES 미만이면 더 이상 페이지 진행하지 않음.
	// 시골 동(저밀도) 과 이미 saturated 된 dong 에서 중복 키워드가 추가 호출을 낭비하는 케이스를 동시에 차단.
	private static final int EARLY_STOP_CHECKPOINT_PAGE = 3;
	private static final int EARLY_STOP_MIN_NEW_UNIQUES = 5;
	// 행정동(예: 장안제1동, 목1동)을 법정동(장안동, 목동)으로 정규화 — Kakao 검색은 법정동 기준
	private static final Pattern ADMIN_DONG_SUFFIX = Pattern.compile("^(.+?)(?:제)?\\d+동$");

	private final KakaoLocalClient kakaoLocalClient;
	private final BulkImportRateLimiter rateLimiter;
	private final BulkImportStoreSaver storeSaver;

	// 법정동 정규화 검색 결과 캐시 — (region1st, region2nd, normalized_dong) 단위.
	// 같은 법정동을 공유하는 여러 행정동(예: 정릉제1동/2동/3동 → 정릉동)이 모두 같은 정규화 쿼리를
	// 수행하던 낭비를 1회로 줄임. 행정동별 검색은 그대로 수행되어 커버리지 손실 없음.
	private final Map<String, Map<String, KakaoPlace>> normalizedSearchCache = new ConcurrentHashMap<>();

	public DongImportResult importDong(String region1st, String region2nd, String region3rd)
			throws InterruptedException {
		Map<String, KakaoPlace> dedup = new HashMap<>();

		// 1) 행정동 자체 검색 — 항상 수행 (행정동별로 고유한 결과 가능)
		for (BulkSearchKeyword keyword : BulkSearchKeyword.values()) {
			String query = "%s %s %s %s".formatted(region1st, region2nd, region3rd, keyword.name());
			collectKeyword(query, dedup, keyword.isTrusted());
		}

		// 2) 법정동 정규화가 다르면 1회만 수행 후 캐시 — 같은 (지역, 법정동) 키로 재사용
		String normalized = normalizeDong(region3rd);
		if (!normalized.equals(region3rd)) {
			String cacheKey = region1st + "|" + region2nd + "|" + normalized;
			Map<String, KakaoPlace> cached = normalizedSearchCache.get(cacheKey);
			if (cached != null) {
				log.info("[BulkImport] 법정동 캐시 히트 - {} {} {} (재사용 {}건)",
					region1st, region2nd, normalized, cached.size());
				dedup.putAll(cached);
			} else {
				log.info("[BulkImport] 동 정규화 검색 - {} → {}", region3rd, normalized);
				Map<String, KakaoPlace> normalizedResults = new HashMap<>();
				for (BulkSearchKeyword keyword : BulkSearchKeyword.values()) {
					String query = "%s %s %s %s".formatted(region1st, region2nd, normalized, keyword.name());
					collectKeyword(query, normalizedResults, keyword.isTrusted());
				}
				normalizedSearchCache.put(cacheKey, normalizedResults);
				dedup.putAll(normalizedResults);
			}
		}

		for (KakaoPlace place : dedup.values()) {
			try {
				storeSaver.save(place);
			} catch (Exception e) {
				log.warn("[BulkImport] place 저장 실패 - placeId={}, name={}, err={}",
					place.id(), place.placeName(), e.getMessage());
			}
		}
		return new DongImportResult(dedup.size(), dedup.size());
	}

	private void collectKeyword(String query, Map<String, KakaoPlace> dedup, boolean trustedKeyword)
			throws InterruptedException {
		int newUniquesThisKeyword = 0;
		for (int page = 1; page <= MAX_PAGES; page++) {
			rateLimiter.acquire();
			KakaoSearchResult result = kakaoLocalClient.searchByKeyword(query, page, trustedKeyword);
			for (KakaoPlace place : result.places()) {
				if (dedup.putIfAbsent(place.id(), place) == null) {
					newUniquesThisKeyword++;
				}
			}
			if (result.isEnd()) {
				return;
			}
			if (page >= EARLY_STOP_CHECKPOINT_PAGE && newUniquesThisKeyword < EARLY_STOP_MIN_NEW_UNIQUES) {
				log.info("[BulkImport] 저밀도 조기 종료 - query='{}' page={} newUniques={}",
					query, page, newUniquesThisKeyword);
				return;
			}
		}
		log.warn("[BulkImport] page cap 도달 - 추가 결과 가능, query='{}', maxPages={}",
			query, MAX_PAGES);
	}

	static String normalizeDong(String dong) {
		Matcher matcher = ADMIN_DONG_SUFFIX.matcher(dong);
		if (matcher.matches()) {
			return matcher.group(1) + "동";
		}
		return dong;
	}

	public record DongImportResult(int foundCount, int insertedCount) {
	}
}
