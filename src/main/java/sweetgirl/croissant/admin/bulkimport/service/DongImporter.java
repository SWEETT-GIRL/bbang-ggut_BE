package sweetgirl.croissant.admin.bulkimport.service;

import java.util.HashMap;
import java.util.Map;

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

	private static final int MAX_PAGES = 3;

	private final KakaoLocalClient kakaoLocalClient;
	private final BulkImportRateLimiter rateLimiter;
	private final BulkImportStoreSaver storeSaver;

	public DongImportResult importDong(String region1st, String region2nd, String region3rd)
			throws InterruptedException {
		Map<String, KakaoPlace> dedup = new HashMap<>();

		for (BulkSearchKeyword keyword : BulkSearchKeyword.values()) {
			String query = "%s %s %s %s".formatted(region1st, region2nd, region3rd, keyword.name());
			collectKeyword(query, dedup);
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

	private void collectKeyword(String query, Map<String, KakaoPlace> dedup) throws InterruptedException {
		for (int page = 1; page <= MAX_PAGES; page++) {
			rateLimiter.acquire();
			KakaoSearchResult result = kakaoLocalClient.searchByKeyword(query, page);
			for (KakaoPlace place : result.places()) {
				dedup.putIfAbsent(place.id(), place);
			}
			if (result.isEnd()) {
				break;
			}
		}
	}

	public record DongImportResult(int foundCount, int insertedCount) {
	}
}
