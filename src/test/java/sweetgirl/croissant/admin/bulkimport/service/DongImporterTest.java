package sweetgirl.croissant.admin.bulkimport.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.admin.bulkimport.code.BulkImportRateLimiter;
import sweetgirl.croissant.admin.bulkimport.enums.BulkSearchKeyword;
import sweetgirl.croissant.external.kakao.client.KakaoLocalClient;
import sweetgirl.croissant.external.kakao.dto.KakaoPlace;
import sweetgirl.croissant.external.kakao.dto.KakaoSearchResult;

@ExtendWith(MockitoExtension.class)
class DongImporterTest {

	@Mock
	KakaoLocalClient kakaoLocalClient;
	@Mock
	BulkImportRateLimiter rateLimiter;
	@Mock
	BulkImportStoreSaver storeSaver;

	@InjectMocks
	DongImporter dongImporter;

	@Test
	@DisplayName("키워드 N개를 모두 검색하고 dedup한 후 각 place에 대해 saver.save를 호출한다")
	void importDong_searchesAllKeywords_dedupesAndSaves() throws Exception {
		KakaoPlace placeA = place("kakao-1", "A빵집");
		KakaoPlace placeB = place("kakao-2", "B빵집");
		KakaoPlace duplicateA = place("kakao-1", "A빵집");

		given(kakaoLocalClient.searchByKeyword(anyString(), anyInt()))
			.willReturn(new KakaoSearchResult(List.of(placeA, placeB), true))
			.willReturn(new KakaoSearchResult(List.of(duplicateA), true))
			.willReturn(new KakaoSearchResult(List.of(), true));

		DongImporter.DongImportResult result = dongImporter.importDong(
			"서울특별시", "강남구", "역삼동");

		int keywordCount = BulkSearchKeyword.values().length;
		verify(kakaoLocalClient, times(keywordCount)).searchByKeyword(anyString(), anyInt());
		verify(rateLimiter, times(keywordCount)).acquire();
		verify(storeSaver).save(placeA);
		verify(storeSaver).save(placeB);
		verify(storeSaver, times(2)).save(org.mockito.ArgumentMatchers.any());
		assertThat(result.foundCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("isEnd=false면 다음 페이지까지 호출한다 (최대 3페이지)")
	void importDong_pagesUntilIsEnd() throws Exception {
		KakaoPlace placeA = place("kakao-A", "A");
		given(kakaoLocalClient.searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(1)))
			.willReturn(new KakaoSearchResult(List.of(placeA), false));
		given(kakaoLocalClient.searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(2)))
			.willReturn(new KakaoSearchResult(List.of(), true));

		dongImporter.importDong("서울특별시", "강남구", "역삼동");

		int keywordCount = BulkSearchKeyword.values().length;
		verify(kakaoLocalClient, times(keywordCount)).searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(1));
		verify(kakaoLocalClient, times(keywordCount)).searchByKeyword(anyString(), org.mockito.ArgumentMatchers.eq(2));
	}

	private KakaoPlace place(String id, String name) {
		return new KakaoPlace(id, name, "addr", "road", "음식점 > 간식", "FD6", null, "127.0", "37.5");
	}
}
