package sweetgirl.croissant.store.service.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

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
			.willReturn(List.of(SearchRegionAlias.builder().alias("성수").normalizedAlias("성수").build()));
		given(searchRegionAliasRepository.findByNormalizedAlias("에그타르트")).willReturn(List.of());

		SearchIntent result = parser.parse(" 성수   에그타르트 ");

		assertThat(result.rawQuery()).isEqualTo("성수 에그타르트");
		assertThat(result.rawTokens()).containsExactly("성수", "에그타르트");
		assertThat(result.regionTokens()).containsExactly("성수");
		assertThat(result.breadTokens()).containsExactly("에그타르트");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_doesNotTreatSuffixTokenAsRegionWhenAliasMissing() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		// 접미사 fallback 폐기: alias에 없으면 '동'으로 끝나도 지역으로 보지 않는다
		given(searchRegionAliasRepository.findByNormalizedAlias("한남동")).willReturn(List.of());
		given(searchRegionAliasRepository.findByNormalizedAlias("소금빵")).willReturn(List.of());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent result = parser.parse("한남동 소금빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("한남동");
	}

	@Test
	void parse_treatsSuffixTokenAsRegionWhenAliasExists() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		// region_3rd 흡수: "한남동"이 alias로 등록돼 있으면 지역으로 인정
		given(searchRegionAliasRepository.findByNormalizedAlias("한남동"))
			.willReturn(List.of(SearchRegionAlias.builder().alias("한남동").normalizedAlias("한남동").build()));
		given(searchRegionAliasRepository.findByNormalizedAlias("소금빵")).willReturn(List.of());

		SearchIntent result = parser.parse("한남동 소금빵");

		assertThat(result.regionTokens()).containsExactly("한남동");
		assertThat(result.breadTokens()).containsExactly("소금빵");
	}

	@Test
	void parse_doesNotTreatBakeryWordAsRegionEvenWhenSuffixMatches() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		// "갤러리"는 "리"로 끝나지만 alias에 없으므로 지역 아님(접미사 추측 폐기 회귀 방지)
		given(searchRegionAliasRepository.findByNormalizedAlias("갤러리")).willReturn(List.of());
		given(searchRegionAliasRepository.findAll()).willReturn(List.of());

		SearchIntent result = parser.parse("갤러리");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.remainingTokens()).containsExactly("갤러리");
	}

	@Test
	void parse_convertsBreadSynonymsToCanonicalToken() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("크로와상")).willReturn(List.of());
		given(searchRegionAliasRepository.findByNormalizedAlias("시오빵")).willReturn(List.of());

		SearchIntent result = parser.parse("크로와상 시오빵");

		assertThat(result.breadTokens()).containsExactly("크루아상", "소금빵");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_keepsExactBreadTypeBeforeSynonymCanonicalization() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("샌드베이글")).willReturn(List.of());

		SearchIntent result = parser.parse("샌드베이글");

		assertThat(result.breadTokens()).containsExactly("샌드베이글");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_keepsUnclassifiedTokensAsRemaining() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("양쯔간루")).willReturn(List.of());
		given(searchRegionAliasRepository.findByNormalizedAlias("쫀득빵")).willReturn(List.of());

		SearchIntent result = parser.parse("양쯔간루 쫀득빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("쫀득빵");
		assertThat(result.remainingTokens()).containsExactly("양쯔간루");
	}

	@Test
	void parse_keepsRemainingAndExactBreadType() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("양쯔간루")).willReturn(List.of());
		given(searchRegionAliasRepository.findByNormalizedAlias("샌드베이글")).willReturn(List.of());

		SearchIntent result = parser.parse("양쯔간루 샌드베이글");

		assertThat(result.breadTokens()).containsExactly("샌드베이글");
		assertThat(result.remainingTokens()).containsExactly("양쯔간루");
	}

	@Test
	void parse_keepsCompoundTokenWholeByDefaultButDecomposesBreadOnFallback() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("초코소금빵")).willReturn(List.of());
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
		given(searchRegionAliasRepository.findByNormalizedAlias("바닐라에그타르트")).willReturn(List.of());
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
		given(searchRegionAliasRepository.findByNormalizedAlias("크로와상맛집")).willReturn(List.of());
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
		given(searchRegionAliasRepository.findByNormalizedAlias("성수초코소금빵")).willReturn(List.of());
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
			.willReturn(List.of(SearchRegionAlias.builder().alias("서울시").normalizedAlias("서울시").build()));
		given(searchRegionAliasRepository.findByNormalizedAlias("성수"))
			.willReturn(List.of(SearchRegionAlias.builder().alias("성수").normalizedAlias("성수").build()));

		SearchIntent result = parser.parse("서울시 성수");

		assertThat(result.regionTokens()).containsExactly("서울시", "성수");
		assertThat(result.breadTokens()).isEmpty();
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_treatsAliasTokenAsRegionWhenMultipleRegionsMatch() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		given(searchRegionAliasRepository.findByNormalizedAlias("중구")).willReturn(List.of(
			SearchRegionAlias.builder().alias("중구").normalizedAlias("중구").build(),
			SearchRegionAlias.builder().alias("중구").normalizedAlias("중구").build()));

		SearchIntent result = parser.parse("중구");

		assertThat(result.regionTokens()).containsExactly("중구");
		assertThat(result.remainingTokens()).isEmpty();
	}

	@Test
	void parse_treatsSingleCharacterTokenAsNonRegionWithoutRepositoryLookup() {
		StoreSearchQueryParser parser = new StoreSearchQueryParser(searchRegionAliasRepository);
		// 길이 1 토큰은 alias 조회 없이 바로 비지역으로 처리된다 (isRegionToken의 길이 가드)
		given(searchRegionAliasRepository.findByNormalizedAlias("소금빵")).willReturn(List.of());

		SearchIntent result = parser.parse("동 소금빵");

		assertThat(result.regionTokens()).isEmpty();
		assertThat(result.breadTokens()).containsExactly("소금빵");
		assertThat(result.remainingTokens()).containsExactly("동");
		verify(searchRegionAliasRepository, never()).findByNormalizedAlias("동");
	}
}
