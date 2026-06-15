package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.List;
import java.util.Optional;

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

	@Test
	void parse_splitsRegionAndBreadTokens() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("성수"))
			.willReturn(Optional.of(SearchRegionAlias.builder().alias("성수").normalizedAlias("성수").build()));
		given(searchRegionAliasRepository.findByNormalizedAlias("에그타르트")).willReturn(Optional.empty());

		SearchIntent result = parser.parse(" 성수   에그타르트 ");

		assertThat(result.rawQuery()).isEqualTo("성수 에그타르트");
		assertThat(result.rawTokens()).containsExactly("성수", "에그타르트");
		assertThat(result.regionTokens()).containsExactly("성수");
		assertThat(result.breadTokens()).containsExactly("에그타르트");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_treatsSuffixTokenAsRegionWhenAliasDoesNotExist() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("한남동")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findByNormalizedAlias("소금빵")).willReturn(Optional.empty());

		SearchIntent result = parser.parse("한남동 소금빵");

		assertThat(result.regionTokens()).containsExactly("한남동");
		assertThat(result.breadTokens()).containsExactly("소금빵");
	}

	@Test
	void parse_convertsBreadSynonymsToCanonicalToken() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("크로와상")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findByNormalizedAlias("시오빵")).willReturn(Optional.empty());

		SearchIntent result = parser.parse("크로와상 시오빵");

		assertThat(result.breadTokens()).containsExactly("크루아상", "소금빵");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_keepsExactBreadTypeBeforeSynonymCanonicalization() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("샌드베이글")).willReturn(Optional.empty());

		SearchIntent result = parser.parse("샌드베이글");

		assertThat(result.breadTokens()).containsExactly("샌드베이글");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_keepsUnclassifiedTokensAsRemaining() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("양쯔간루")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findByNormalizedAlias("쫀득빵")).willReturn(Optional.empty());

		SearchIntent result = parser.parse("양쯔간루 쫀득빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("쫀득빵");
		assertThat(result.remainingTokens()).containsExactly("양쯔간루");
	}

	@Test
	void parse_keepsRemainingAndExactBreadType() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("양쯔간루")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findByNormalizedAlias("샌드베이글")).willReturn(Optional.empty());

		SearchIntent result = parser.parse("양쯔간루 샌드베이글");

		assertThat(result.breadTokens()).containsExactly("샌드베이글");
		assertThat(result.remainingTokens()).containsExactly("양쯔간루");
	}

	@Test
	void parse_keepsCompoundTokenWholeByDefaultButDecomposesBreadOnFallback() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("초코소금빵")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		// 기본(가게명 우선): 지역어 없이 빵만 박힌 붙어쓴 토큰은 분해하지 않고 통째로 둔다
		SearchIntent primary = parser.parse("초코소금빵");
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("초코소금빵");

		// 폴백: "소금빵"을 빵 must로 분해하고 남은 "초코"는 remaining으로 둔다
		SearchIntent fallback = parser.parseWithBreadFallback("초코소금빵");
		assertThat(fallback.breadTokens()).containsExactly("소금빵");
		assertThat(fallback.remainingTokens()).containsExactly("초코");
	}

	@Test
	void parse_keepsMenuNameLikeTokenWholeByDefaultButDecomposesBreadOnFallback() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("바닐라에그타르트")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent primary = parser.parse("바닐라에그타르트");
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("바닐라에그타르트");

		SearchIntent fallback = parser.parseWithBreadFallback("바닐라에그타르트");
		assertThat(fallback.breadTokens()).containsExactly("에그타르트");
		assertThat(fallback.remainingTokens()).containsExactly("바닐라");
	}

	@Test
	void parse_keepsSynonymCompoundWholeByDefaultButDecomposesToCanonicalOnFallback() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("크로와상맛집")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent primary = parser.parse("크로와상맛집");
		assertThat(primary.breadTokens()).isEmpty();
		assertThat(primary.remainingTokens()).containsExactly("크로와상맛집");

		SearchIntent fallback = parser.parseWithBreadFallback("크로와상맛집");
		assertThat(fallback.breadTokens()).containsExactly("크루아상");
		assertThat(fallback.remainingTokens()).containsExactly("맛집");
	}

	@Test
	void parse_extractsContainedRegionBreadAndRemainingTokens() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("성수초코소금빵")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of(
			SearchRegionAlias.builder().alias("성수").normalizedAlias("성수").build()));

		SearchIntent result = parser.parse("성수초코소금빵");

		assertThat(result.regionTokens()).containsExactly("성수");
		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("초코");
	}

	@Test
	void parse_extractsMultipleRegionTokensFromAliases() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("서울시"))
			.willReturn(Optional.of(SearchRegionAlias.builder().alias("서울시").normalizedAlias("서울시").build()));
		given(searchRegionAliasRepository.findByNormalizedAlias("성수"))
			.willReturn(Optional.of(SearchRegionAlias.builder().alias("성수").normalizedAlias("성수").build()));

		SearchIntent result = parser.parse("서울시 성수");

		assertThat(result.regionTokens()).containsExactly("서울시", "성수");
		assertThat(result.breadTokens()).isEmpty();
		assertThat(result.remainingTokens()).isEmpty();
	}
}
