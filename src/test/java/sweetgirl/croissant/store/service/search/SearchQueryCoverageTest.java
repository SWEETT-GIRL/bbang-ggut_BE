package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.external.opensearch.document.SearchRegionDto;
import sweetgirl.croissant.external.opensearch.document.StoreDocument;
import sweetgirl.croissant.external.opensearch.document.StoreMenuDto;

class SearchQueryCoverageTest {

	private final SearchQueryCoverage searchQueryCoverage = new SearchQueryCoverage();

	// StoreSearchQueryParser가 실제로 만들어내는 SearchIntent 형태를 그대로 재현한다.
	private SearchIntent intent(String rawQuery, String cleanQuery, List<String> rawTokens,
			List<String> regionTokens, List<String> breadTokens, List<String> remainingTokens) {
		return new SearchIntent(rawQuery, cleanQuery, rawTokens, regionTokens, breadTokens, remainingTokens);
	}

	private StoreDocument document(String storeName, List<String> breadTypes, List<String> menuNames,
			List<String> regionAliases) {
		return document(storeName, List.of(), breadTypes, menuNames, regionAliases);
	}

	private StoreDocument document(String storeName, List<String> storeNameAliases, List<String> breadTypes,
			List<String> menuNames, List<String> regionAliases) {
		return new StoreDocument(
			1L,
			storeName,
			storeNameAliases,
			"서울 성동구 성수동2가 1-1",
			null,
			regionAliases.stream().map(name -> new SearchRegionDto(1L, name, "DONG", 1.0)).toList(),
			regionAliases,
			breadTypes,
			menuNames.stream().map(name -> new StoreMenuDto(1L, name, null)).toList(),
			0.0);
	}

	@Test
	@DisplayName("조합 검색 — 지역 토큰이 지역 필드에, 빵 토큰이 메뉴 필드에 있으면 커버로 본다")
	void covers_combinationQueryMatchedAcrossFields() {
		SearchIntent intent = intent("성수동 소금빵", "성수동 소금빵", List.of("성수동", "소금빵"),
			List.of("성수동"), List.of("소금빵"), List.of());
		StoreDocument document = document("성수베이커리", List.of("소금빵"), List.of("소금빵"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isTrue();
	}

	@Test
	@DisplayName("조합 검색 — 빵 토큰이 메뉴·빵종류 어디에도 없으면 커버가 아니다")
	void covers_combinationQueryMissingBreadToken() {
		SearchIntent intent = intent("성수동 소금빵", "성수동 소금빵", List.of("성수동", "소금빵"),
			List.of("성수동"), List.of("소금빵"), List.of());
		StoreDocument document = document("성수베이커리", List.of("케이크"), List.of("당근케이크"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isFalse();
	}

	@Test
	@DisplayName("동의어 검색 — breadTypes가 대표어를 들고 있으면 커버로 본다")
	void covers_breadSynonymMatchedByCanonicalCategory() {
		// "시오빵"은 파서가 대표어 "소금빵"으로 치환한다.
		SearchIntent intent = intent("시오빵", "시오빵", List.of("시오빵"),
			List.of(), List.of("소금빵"), List.of());
		StoreDocument document = document("성수베이커리", List.of("소금빵"), List.of("시오빵"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isTrue();
	}

	@Test
	@DisplayName("사전에 없는 말이 섞이면 통째로 대조한다 — 가게명에 검색어 전체가 있으면 커버")
	void covers_wholeQueryMatchedByStoreName() {
		SearchIntent intent = intent("맘모스 베이커리", "맘모스 베이커리", List.of("맘모스", "베이커리"),
			List.of(), List.of("맘모스"), List.of("베이커리"));
		StoreDocument document = document("맘모스 베이커리", List.of("맘모스"), List.of("맘모스빵"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isTrue();
	}

	@Test
	@DisplayName("사전에 없는 말이 섞이면 토큰을 쪼개지 않는다 — 서로 다른 필드에 흩어져 걸린 무관한 가게는 커버가 아니다")
	void covers_wholeQueryNotSatisfiedByScatteredTokens() {
		// "맘모스 베이커리"를 토큰으로 쪼개 대조하면 메뉴에 맘모스가 있고 이름에 베이커리가 든
		// 어글리 베이커리가 통과해버린다. 통째 대조라야 걸러진다.
		SearchIntent intent = intent("맘모스 베이커리", "맘모스 베이커리", List.of("맘모스", "베이커리"),
			List.of(), List.of("맘모스"), List.of("베이커리"));
		StoreDocument document = document("어글리 베이커리", List.of("맘모스"), List.of("맘모스"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isFalse();
	}

	@Test
	@DisplayName("불용어만 잔여 토큰으로 남으면 조합 검색으로 판정한다")
	void covers_stopWordRemainingTokenIgnored() {
		// 파서는 불용어를 cleanQuery에서만 빼고 잔여 토큰에는 남긴다. "빵집" 때문에 통째 대조로
		// 넘어가면 정상 검색이 실패로 잡히므로 cleanQuery에 없는 잔여는 무시해야 한다.
		SearchIntent intent = intent("성수동 빵집", "성수동", List.of("성수동", "빵집"),
			List.of("성수동"), List.of(), List.of("빵집"));
		StoreDocument document = document("어글리 베이커리", List.of("소금빵"), List.of("소금빵"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isTrue();
	}

	@Test
	@DisplayName("구명 검색 — 검색어 전체가 storeNameAliases에 있으면 커버로 본다")
	void covers_wholeQueryMatchedByStoreNameAlias() {
		// 개명 가게("HEY DOUGH")를 구명("헤이도우")으로 검색 — alias 가 커버를 인정해야
		// 검색 품질 지표가 개명 가게 검색을 실패로 오집계하지 않는다.
		SearchIntent intent = intent("헤이도우", "헤이도우", List.of("헤이도우"),
			List.of(), List.of(), List.of("헤이도우"));
		StoreDocument document = document("HEY DOUGH", List.of("헤이도우"),
			List.of("소금빵"), List.of("소금빵"), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isTrue();
	}

	@Test
	@DisplayName("공백·특수문자는 정규화 후 대조한다")
	void covers_normalizesWhitespaceAndSpecialCharacters() {
		SearchIntent intent = intent("우드 앤 브릭", "우드 앤 브릭", List.of("우드", "앤", "브릭"),
			List.of(), List.of(), List.of("우드", "앤", "브릭"));
		StoreDocument document = document("우드앤브릭", List.of(), List.of(), List.of("성수동"));

		assertThat(searchQueryCoverage.covers(intent, document)).isTrue();
	}

	@Test
	@DisplayName("document가 null이면 커버가 아니다")
	void covers_nullDocument() {
		SearchIntent intent = intent("소금빵", "소금빵", List.of("소금빵"),
			List.of(), List.of("소금빵"), List.of());

		assertThat(searchQueryCoverage.covers(intent, null)).isFalse();
	}

	@Test
	@DisplayName("isFullyClassified — 전부 지역·빵으로 분류되면 true, 사전에 없는 말이 남으면 false")
	void isFullyClassified_dependsOnMeaningfulRemainingTokens() {
		SearchIntent classified = intent("성수동 소금빵", "성수동 소금빵", List.of("성수동", "소금빵"),
			List.of("성수동"), List.of("소금빵"), List.of());
		SearchIntent unclassified = intent("맘모스 베이커리", "맘모스 베이커리", List.of("맘모스", "베이커리"),
			List.of(), List.of("맘모스"), List.of("베이커리"));
		SearchIntent stopWordOnly = intent("성수동 빵집", "성수동", List.of("성수동", "빵집"),
			List.of("성수동"), List.of(), List.of("빵집"));

		assertThat(searchQueryCoverage.isFullyClassified(classified)).isTrue();
		assertThat(searchQueryCoverage.isFullyClassified(unclassified)).isFalse();
		assertThat(searchQueryCoverage.isFullyClassified(stopWordOnly)).isTrue();
	}
}
