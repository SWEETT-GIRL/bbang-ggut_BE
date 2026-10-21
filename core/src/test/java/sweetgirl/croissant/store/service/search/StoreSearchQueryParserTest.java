package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.store.entity.SearchRegionAlias;
import sweetgirl.croissant.store.repository.SearchRegionAliasRepository;

@ExtendWith(MockitoExtension.class)
class StoreSearchQueryParserTest {

	@Mock
	private SearchRegionAliasRepository searchRegionAliasRepository;

	// 지역 alias는 부팅 시 findAll()로 1회 캐싱된다. 테스트는 캐시에 담길 alias만 stub하고,
	// 첫 parse() 호출 시 지연 적재된다(단위 테스트에는 ApplicationReadyEvent가 발생하지 않으므로).
	private StoreSearchQueryParser parserWithRegionAliases(String... normalizedAliases) {
		given(searchRegionAliasRepository.findAll()).willReturn(
			Arrays.stream(normalizedAliases)
				.map(alias -> SearchRegionAlias.builder().alias(alias).normalizedAlias(alias).build())
				.toList());
		return new StoreSearchQueryParser(searchRegionAliasRepository);
	}

	@Test
	void parse_splitsRegionAndBreadTokens() {
		StoreSearchQueryParser parser = parserWithRegionAliases("성수");

		SearchIntent result = parser.parse(" 성수   에그타르트 ");

		assertThat(result.rawQuery()).isEqualTo("성수 에그타르트");
		assertThat(result.rawTokens()).containsExactly("성수", "에그타르트");
		assertThat(result.regionTokens()).containsExactly("성수");
		assertThat(result.breadTokens()).containsExactly("에그타르트");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_doesNotTreatSuffixTokenAsRegionWhenAliasMissing() {
		// 접미사 fallback 폐기: alias에 없으면 '동'으로 끝나도 지역으로 보지 않는다
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("한남동 소금빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("한남동");
	}

	@Test
	void parse_treatsSuffixTokenAsRegionWhenAliasExists() {
		// region_3rd 흡수: "한남동"이 alias로 등록돼 있으면 지역으로 인정
		StoreSearchQueryParser parser = parserWithRegionAliases("한남동");

		SearchIntent result = parser.parse("한남동 소금빵");

		assertThat(result.regionTokens()).containsExactly("한남동");
		assertThat(result.breadTokens()).containsExactly("소금빵");
	}

	@Test
	void parse_doesNotTreatBakeryWordAsRegionEvenWhenSuffixMatches() {
		// "갤러리"는 "리"로 끝나지만 alias에 없으므로 지역 아님(접미사 추측 폐기 회귀 방지)
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("갤러리");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.remainingTokens()).containsExactly("갤러리");
	}

	@Test
	void parse_convertsBreadSynonymsToCanonicalToken() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("크로와상 시오빵");

		assertThat(result.breadTokens()).containsExactly("크루아상", "소금빵");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_keepsExactBreadTypeBeforeSynonymCanonicalization() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("샌드베이글");

		assertThat(result.breadTokens()).containsExactly("샌드베이글");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_keepsUnclassifiedTokensAsRemaining() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("양쯔간루 쫀득빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("쫀득빵");
		assertThat(result.remainingTokens()).containsExactly("양쯔간루");
	}

	@Test
	void parse_keepsRemainingAndExactBreadType() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("양쯔간루 샌드베이글");

		assertThat(result.breadTokens()).containsExactly("샌드베이글");
		assertThat(result.remainingTokens()).containsExactly("양쯔간루");
	}

	@Test
	void parse_keepsCompoundTokenWholeByDefaultButDecomposesBreadOnFallback() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		// 기본(가게명 우선): 지역어 없이 빵만 박힌 붙어쓴 토큰은 분해하지 않고 통째로 둔다
		SearchIntent primary = parser.parse("초코소금빵");
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("초코소금빵");

		// 폴백: "소금빵"을 빵 must로 분해하고 남은 "초코"는 remaining으로 둔다
		SearchIntent fallback = parser.parseWithGluedTokenFallback("초코소금빵");
		assertThat(fallback.breadTokens()).containsExactly("소금빵");
		assertThat(fallback.remainingTokens()).containsExactly("초코");
	}

	@Test
	void parse_keepsMenuNameLikeTokenWholeByDefaultButDecomposesBreadOnFallback() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent primary = parser.parse("바닐라에그타르트");
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("바닐라에그타르트");

		SearchIntent fallback = parser.parseWithGluedTokenFallback("바닐라에그타르트");
		assertThat(fallback.breadTokens()).containsExactly("에그타르트");
		assertThat(fallback.remainingTokens()).containsExactly("바닐라");
	}

	@Test
	void parse_keepsSynonymCompoundWholeByDefaultButDecomposesToCanonicalOnFallback() {
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent primary = parser.parse("크로와상맛집");
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("크로와상맛집");

		SearchIntent fallback = parser.parseWithGluedTokenFallback("크로와상맛집");
		assertThat(fallback.breadTokens()).containsExactly("크루아상");
		assertThat(fallback.remainingTokens()).containsExactly("맛집");
	}

	@Test
	@DisplayName("지역+빵이 붙어쓴 토큰도 1차에서는 통째로 두고 폴백에서만 분해한다")
	void parse_keepsRegionBreadCompoundWholeByDefaultButDecomposesOnFallback() {
		StoreSearchQueryParser parser = parserWithRegionAliases("성수");

		SearchIntent primary = parser.parse("성수초코소금빵");
		assertThat(primary.regionTokens()).isEmpty();
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("성수초코소금빵");

		SearchIntent fallback = parser.parseWithGluedTokenFallback("성수초코소금빵");
		assertThat(fallback.regionTokens()).containsExactly("성수");
		assertThat(fallback.breadTokens()).containsExactly("소금빵");
		assertThat(fallback.remainingTokens()).containsExactly("초코");
	}

	@Test
	@DisplayName("가게명에 지역어가 박혀 있어도 1차에서 분해하지 않는다 — 소금도화가 도화동 가게들에 묻히던 문제")
	void parse_doesNotDecomposeRegionInsideStoreName() {
		StoreSearchQueryParser parser = parserWithRegionAliases("도화");

		SearchIntent primary = parser.parse("소금도화");

		// 분해하면 "도화" 지역 가게가 통째로 결과셋에 들어와 정작 "소금도화"가 밀린다
		assertThat(primary.regionTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("소금도화");
	}

	@Test
	@DisplayName("지역어만 박힌 붙어쓴 토큰도 폴백에서는 분해된다")
	void parse_decomposesRegionOnlyCompoundOnFallback() {
		StoreSearchQueryParser parser = parserWithRegionAliases("도화");

		SearchIntent fallback = parser.parseWithGluedTokenFallback("도화베이커리");

		assertThat(fallback.regionTokens()).containsExactly("도화");
		assertThat(fallback.remainingTokens()).containsExactly("베이커리");
	}

	@Test
	@DisplayName("공백으로 띄어쓴 지역어는 1차에서도 그대로 지역 토큰이다")
	void parse_keepsSpacedRegionTokenInPrimary() {
		StoreSearchQueryParser parser = parserWithRegionAliases("성수동");

		SearchIntent primary = parser.parse("성수동 소금빵");

		// 붙어쓴 토큰만 통째로 두는 것이지, 정확 일치 경로는 영향받지 않는다
		assertThat(primary.regionTokens()).containsExactly("성수동");
		assertThat(primary.breadTokens()).containsExactly("소금빵");
		assertThat(primary.remainingTokens()).isEmpty();
	}

	@Test
	void parse_extractsMultipleRegionTokensFromAliases() {
		StoreSearchQueryParser parser = parserWithRegionAliases("서울시", "성수");

		SearchIntent result = parser.parse("서울시 성수");

		assertThat(result.regionTokens()).containsExactly("서울시", "성수");
		assertThat(result.breadTokens()).isEmpty();
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_treatsAliasTokenAsRegionWhenMultipleRegionsMatch() {
		StoreSearchQueryParser parser = parserWithRegionAliases("중구");

		SearchIntent result = parser.parse("중구");

		assertThat(result.regionTokens()).containsExactly("중구");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_treatsSingleCharacterTokenAsNonRegion() {
		// 길이 1 토큰은 지역 판정(길이 가드)에서 바로 비지역 처리된다
		StoreSearchQueryParser parser = parserWithRegionAliases();

		SearchIntent result = parser.parse("동 소금빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("동");
	}

	@Test
	void parse_survivesRegionAliasLoadFailure() {
		// 지역 alias 로드가 실패해도 검색이 죽지 않고, 지역 판정만 건너뛴다(NPE 없이, 다음 호출서 재시도).
		given(searchRegionAliasRepository.findAll()).willThrow(new RuntimeException("db down"));
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);

		SearchIntent result = parser.parse("성수 소금빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("성수");
	}
}
