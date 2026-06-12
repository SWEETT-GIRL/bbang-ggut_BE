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
	void parse_extractsContainedBreadTokenFromCompoundToken() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("초코소금빵")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent result = parser.parse("초코소금빵");

		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("초코");
	}

	@Test
	void parse_extractsContainedBreadTokenFromMenuNameLikeToken() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("바닐라에그타르트")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent result = parser.parse("바닐라에그타르트");

		assertThat(result.breadTokens()).containsExactly("에그타르트");
		assertThat(result.remainingTokens()).containsExactly("바닐라");
	}

	@Test
	void parse_extractsContainedBreadSynonymAsCanonicalToken() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("크로와상맛집")).willReturn(Optional.empty());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent result = parser.parse("크로와상맛집");

		assertThat(result.breadTokens()).containsExactly("크루아상");
		assertThat(result.remainingTokens()).containsExactly("맛집");
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
