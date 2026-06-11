package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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
		assertThat(result.remainingTokens()).containsExactly("에그타르트");
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
}
