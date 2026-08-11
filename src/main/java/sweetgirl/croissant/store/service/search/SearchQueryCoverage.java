package sweetgirl.croissant.store.service.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import sweetgirl.croissant.external.opensearch.document.SearchRegionDto;
import sweetgirl.croissant.external.opensearch.document.StoreDocument;
import sweetgirl.croissant.external.opensearch.document.StoreMenuDto;

/**
 * 검색 결과 문서가 사용자가 실제로 입력한 말을 담고 있는지 판정한다.
 *
 * <p>가게 인덱스는 2글자 ngram으로 부분 일치를 허용하고 서브필드 clause가 OR로 묶여 있어
 * 검색어와 사실상 무관한 가게도 결과에 섞인다. 그 비율을 지표로 남기기 위한 사후 검증이며,
 * 검색 결과 자체를 걸러내지는 않는다.
 *
 * <p>판정 방식은 사전에 없는 말이 남았는지로 갈린다.
 * <ul>
 *   <li>전부 지역·빵으로 분류됐으면 토큰을 담당 필드에서 각각 찾는다("성수동 소금빵").</li>
 *   <li>사전에 없는 말이 남았으면 가게명일 가능성이 높으므로 쪼개지 않고 통째로 찾는다.
 *       "맘모스 베이커리"를 쪼개면 메뉴에 맘모스가 있고 이름에 베이커리가 든 무관한 가게가
 *       통과해버리기 때문이다.</li>
 * </ul>
 */
@Component
public class SearchQueryCoverage {

	private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[^a-zA-Z0-9가-힣]");

	public boolean covers(SearchIntent intent, StoreDocument document) {
		if (intent == null || document == null) {
			return false;
		}
		if (isFullyClassified(intent)) {
			return coversClassifiedTokens(intent, document);
		}
		return coversWholeQuery(intent, document);
	}

	/**
	 * 검색어가 전부 지역·빵 사전에 잡혔는지 — 즉 토큰을 쪼개 대조해도 되는 조합 검색인지.
	 *
	 * <p>판정 신뢰도를 가르는 기준이기도 하다. 사전에 있는 말들만 쓴 검색은 대조가 정확하지만,
	 * 사전에 없는 말이 섞이면 통째 대조로 넘어가면서 오탐 가능성이 생긴다.
	 */
	public boolean isFullyClassified(SearchIntent intent) {
		if (intent == null) {
			return false;
		}
		if (!meaningfulRemainingTokens(intent).isEmpty()) {
			return false;
		}
		return !intent.regionTokens().isEmpty() || !intent.breadTokens().isEmpty();
	}

	/**
	 * 불용어로 걸러진 잔여 토큰은 판정에서 제외한다.
	 *
	 * <p>파서는 불용어를 {@code cleanQuery}에서만 빼고 토큰 분류에는 그대로 남긴다. 그래서
	 * "성수동 빵집"은 잔여 토큰 "빵집"이 남는데, 이걸 사전에 없는 말로 취급하면 통째 대조로
	 * 넘어가 정상 검색이 실패로 잡힌다. {@code cleanQuery}에 살아남은 것만 의미 있는 잔여로 본다.
	 */
	private List<String> meaningfulRemainingTokens(SearchIntent intent) {
		String normalizedCleanQuery = normalize(intent.cleanQuery());
		return intent.remainingTokens().stream()
			.map(this::normalize)
			.filter(token -> !token.isBlank())
			.filter(normalizedCleanQuery::contains)
			.toList();
	}

	// 조합 검색: 지역 토큰은 지역 필드에서, 빵 토큰은 메뉴·빵종류 필드에서 각각 찾는다.
	private boolean coversClassifiedTokens(SearchIntent intent, StoreDocument document) {
		List<String> regionTexts = normalizedRegionTexts(document);
		for (String regionToken : intent.regionTokens()) {
			if (!containsToken(regionTexts, regionToken)) {
				return false;
			}
		}
		List<String> breadTexts = normalizedBreadTexts(document);
		for (String breadToken : intent.breadTokens()) {
			if (!containsToken(breadTexts, breadToken)) {
				return false;
			}
		}
		return true;
	}

	// 가게명 검색으로 보고 검색어 전체를 한 필드에서 통째로 찾는다.
	// 원문(rawQuery)이 아니라 불용어를 뺀 cleanQuery로 대조해야 "성수동 맛집"류가 실패로 잡히지 않는다.
	private boolean coversWholeQuery(SearchIntent intent, StoreDocument document) {
		String normalizedQuery = normalize(intent.cleanQuery());
		if (normalizedQuery.isBlank()) {
			return false;
		}
		List<String> texts = new ArrayList<>();
		addNormalized(texts, document.storeName());
		texts.addAll(normalizedBreadTexts(document));
		texts.addAll(normalizedAliasTexts(document));
		return texts.stream().anyMatch(text -> text.contains(normalizedQuery));
	}

	private boolean containsToken(List<String> texts, String token) {
		String normalizedToken = normalize(token);
		if (normalizedToken.isBlank()) {
			return true;
		}
		return texts.stream().anyMatch(text -> text.contains(normalizedToken));
	}

	// 지역 토큰 대조 대상. 주소에도 지역명이 그대로 들어 있어 함께 본다.
	private List<String> normalizedRegionTexts(StoreDocument document) {
		List<String> texts = normalizedAliasTexts(document);
		addNormalized(texts, document.address());
		return texts;
	}

	private List<String> normalizedAliasTexts(StoreDocument document) {
		List<String> texts = new ArrayList<>();
		for (String alias : nullSafeList(document.searchRegionAliases())) {
			addNormalized(texts, alias);
		}
		for (SearchRegionDto region : nullSafeList(document.searchRegions())) {
			if (region != null) {
				addNormalized(texts, region.name());
			}
		}
		return texts;
	}

	// 빵 토큰 대조 대상. breadTypes는 MenuCategory 대표어라 동의어("시오빵"→"소금빵") 대조도 여기서 걸린다.
	private List<String> normalizedBreadTexts(StoreDocument document) {
		List<String> texts = new ArrayList<>();
		for (String breadType : nullSafeList(document.breadTypes())) {
			addNormalized(texts, breadType);
		}
		for (StoreMenuDto menu : nullSafeList(document.menus())) {
			if (menu != null) {
				addNormalized(texts, menu.name());
			}
		}
		return texts;
	}

	private void addNormalized(List<String> texts, String value) {
		String normalized = normalize(value);
		if (!normalized.isBlank()) {
			texts.add(normalized);
		}
	}

	private <T> List<T> nullSafeList(List<T> values) {
		return values != null ? values : List.of();
	}

	private String normalize(String text) {
		if (text == null) {
			return "";
		}
		return NORMALIZE_PATTERN.matcher(text)
			.replaceAll("")
			.toLowerCase(Locale.ROOT);
	}
}
