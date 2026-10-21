package sweetgirl.dataops.snapshot.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.BlogReview;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.BroadcastInfo;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.RecommendMenu;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.ReviewMenu;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.ReviewTheme;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.TopPhoto;
import sweetgirl.dataops.snapshot.parser.ParsedStoreSignal.VotedKeyword;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * 네이버 플레이스 __APOLLO_STATE__ 에서 가게 신호(태그 소스)를 뽑는 파서 — NaverPlaceParser 와 같은 원본을 읽되
 * 운영 반영 필드가 아니라 취향 판정 재료를 꺼낸다 (코스 추천 1단계, 2026-09-04 스냅샷 18,938곳 실측 기준).
 *
 * 필드 위치 (placeDetail = ROOT_QUERY.placeDetail({...}), base = PlaceDetailBase:*):
 * - category·conveniences·microReviews·visitorReviewsScore/Total → base
 * - ratingReviewsTotal → visitorReviewStats 참조를 해소한 노드 자체 (analysis 의 부모)
 * - description·newOpening·broadcastInfos·informationTab.keywordList·topPhotos·images·fsasReviews → placeDetail
 * - votedKeyword·analysis.menus·themes → placeDetail.visitorReviewStats 가 {"__ref": "VisitorReviewStatsResult:id"}
 *   참조로 오므로 해소해야 보인다 (리뷰 없는 가게는 인라인 객체에 analysis:null)
 * - 대표 메뉴 → Menu 엔티티 중 recommend=true
 * - 영업시간 → NaverPlaceParser 의 위치 규칙(newBusinessHours)을 그대로 빌려 BusinessHoursParser 로 구조화
 * placeDetail.themes 는 실측상 [null, …] 뿐이라 읽지 않는다.
 */
@Component
public class StoreSignalParser {

	private static final String REF_FIELD = "__ref";
	private static final String TYPENAME_FIELD = "__typename";
	private static final String BASE_KEY_PREFIX = "PlaceDetailBase";
	private static final String MENU_TYPENAME = "Menu";
	private static final String PLACE_DETAIL_PREFIX = "placeDetail(";

	private final NaverPlaceParser naverPlaceParser;
	private final BusinessHoursParser businessHoursParser;

	public StoreSignalParser(NaverPlaceParser naverPlaceParser, BusinessHoursParser businessHoursParser) {
		this.naverPlaceParser = naverPlaceParser;
		this.businessHoursParser = businessHoursParser;
	}

	public ParsedStoreSignal parse(JsonNode apolloState) {
		JsonNode base = findBaseNode(apolloState);
		JsonNode placeDetail = findFieldByPrefix(apolloState.path("ROOT_QUERY"), PLACE_DETAIL_PREFIX);
		// ratingReviewsTotal 은 analysis 가 아니라 그 부모(VisitorReviewStatsResult)에 있어 노드를 따로 잡는다
		JsonNode reviewStats = resolveRef(apolloState, placeDetail.path("visitorReviewStats"));
		JsonNode analysis = reviewStats.path("analysis");
		return new ParsedStoreSignal(
			textOrNull(base, "category"),
			textOrNull(base, "categoryCode"),
			blankToNull(textOrNull(placeDetail, "description")),
			stringList(placeDetail.path("informationTab").path("keywordList")),
			stringList(base.path("conveniences")),
			stringList(base.path("microReviews")),
			doubleOrNull(base, "visitorReviewsScore"),
			intOrNull(base, "visitorReviewsTotal"),
			intOrNull(reviewStats, "ratingReviewsTotal"),
			parseVotedKeywords(analysis.path("votedKeyword").path("details")),
			parseReviewMenus(analysis.path("menus")),
			parseReviewThemes(analysis.path("themes")),
			parseRecommendMenus(apolloState),
			parseBroadcastInfos(placeDetail.path("broadcastInfos")),
			placeDetail.path("newOpening").asBoolean(false),
			parseTopPhotos(apolloState, placeDetail.path("topPhotos").path("items")),
			intOrNull(placeDetail.path("topPhotos"), "total"),
			intOrNull(placeDetail.path("images"), "totalImages"),
			parseBlogReviews(apolloState, placeDetail.path("fsasReviews").path("items")),
			parseBusinessHours(apolloState));
	}

	private List<BusinessHourRow> parseBusinessHours(JsonNode apolloState) {
		JsonNode isolated = apolloState.deepCopy();
		String raw;
		try { raw = naverPlaceParser.parseBusinessHoursRaw(isolated); }
		finally {
			if (!isolated.equals(apolloState)) throw new IllegalStateException("PARSER_INPUT_MUTATION");
		}
		return raw == null ? List.of() : businessHoursParser.parse(raw);
	}

	private List<VotedKeyword> parseVotedKeywords(JsonNode details) {
		List<VotedKeyword> keywords = new ArrayList<>();
		forEachObject(details, detail -> keywords.add(new VotedKeyword(
			textOrNull(detail, "code"),
			textOrNull(detail, "displayName"),
			detail.path("count").asInt(0))));
		return keywords;
	}

	private List<ReviewMenu> parseReviewMenus(JsonNode menus) {
		List<ReviewMenu> reviewMenus = new ArrayList<>();
		forEachObject(menus, menu -> reviewMenus.add(new ReviewMenu(
			textOrNull(menu, "label"),
			menu.path("count").asInt(0))));
		return reviewMenus;
	}

	private List<ReviewTheme> parseReviewThemes(JsonNode themes) {
		List<ReviewTheme> reviewThemes = new ArrayList<>();
		forEachObject(themes, theme -> reviewThemes.add(new ReviewTheme(
			textOrNull(theme, "code"),
			textOrNull(theme, "label"),
			theme.path("count").asInt(0))));
		return reviewThemes;
	}

	/** 대표 메뉴는 Menu 엔티티의 recommend 플래그 — 메뉴 목록 순서(index)대로 온다 */
	private List<RecommendMenu> parseRecommendMenus(JsonNode apolloState) {
		List<RecommendMenu> recommendMenus = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : apolloState.properties()) {
			JsonNode candidate = entry.getValue();
			if (MENU_TYPENAME.equals(textOrNull(candidate, TYPENAME_FIELD))
				&& candidate.path("recommend").asBoolean(false)) {
				recommendMenus.add(new RecommendMenu(
					textOrNull(candidate, "name"),
					blankToNull(textOrNull(candidate, "price")),
					blankToNull(textOrNull(candidate, "description")),
					textOrSelf(candidate.path("images").path(0))));
			}
		}
		return recommendMenus;
	}

	private List<BroadcastInfo> parseBroadcastInfos(JsonNode broadcastInfos) {
		List<BroadcastInfo> infos = new ArrayList<>();
		forEachObject(broadcastInfos, info -> infos.add(new BroadcastInfo(
			textOrNull(info, "program"),
			textOrNull(info, "channel"),
			textOrNull(info, "date"),
			textOrNull(info, "menu"))));
		return infos;
	}

	/** topPhotos.items 는 PlaceDetailTopPhotoItem 참조 배열 — 영상(video_/clip_)도 originalUrl 은 정지 이미지라 그대로 둔다 */
	private List<TopPhoto> parseTopPhotos(JsonNode apolloState, JsonNode items) {
		List<TopPhoto> photos = new ArrayList<>();
		forEachObject(items, item -> {
			JsonNode photo = resolveRef(apolloState, item);
			String url = textOrNull(photo, "originalUrl");
			if (url != null) {
				photos.add(new TopPhoto(textOrNull(photo, "id"), url, textOrNull(photo, "thumbnailUrl")));
			}
		});
		return photos;
	}

	private List<BlogReview> parseBlogReviews(JsonNode apolloState, JsonNode items) {
		List<BlogReview> reviews = new ArrayList<>();
		forEachObject(items, item -> {
			JsonNode review = resolveRef(apolloState, item);
			reviews.add(new BlogReview(
				textOrNull(review, "title"),
				textOrNull(review, "contents"),
				textOrNull(review, "url"),
				textOrNull(review, "date")));
		});
		return reviews;
	}

	private JsonNode findBaseNode(JsonNode apolloState) {
		for (Map.Entry<String, JsonNode> entry : apolloState.properties()) {
			if (entry.getKey().startsWith(BASE_KEY_PREFIX)) {
				return entry.getValue();
			}
		}
		return MissingNode.getInstance();
	}

	/** ROOT_QUERY 필드 키에 GraphQL 인자가 포함되므로 접두 매칭 (NaverPlaceParser 와 동일 규칙) */
	private JsonNode findFieldByPrefix(JsonNode node, String prefix) {
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				if (entry.getKey().startsWith(prefix)) {
					return entry.getValue();
				}
			}
		}
		return MissingNode.getInstance();
	}

	/** {"__ref": "키"} 한 단계 해소 — 참조가 아니면(인라인 객체) 그대로 돌려준다 */
	private JsonNode resolveRef(JsonNode apolloState, JsonNode node) {
		if (node.isObject() && node.has(REF_FIELD)) {
			return apolloState.path(node.get(REF_FIELD).asString());
		}
		return node;
	}

	private void forEachObject(JsonNode array, Consumer<JsonNode> consumer) {
		if (array.isArray()) {
			array.forEach(element -> {
				if (element.isObject()) {
					consumer.accept(element);
				}
			});
		}
	}

	private List<String> stringList(JsonNode array) {
		List<String> values = new ArrayList<>();
		if (array.isArray()) {
			array.forEach(element -> {
				if (element.isString() && !element.asString().isBlank()) {
					values.add(element.asString());
				}
			});
		}
		return values;
	}

	/** 점수는 "4.9" 문자열로 온다 — 숫자 변환 실패 시 null */
	private Double doubleOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isNumber()) {
			return value.asDouble();
		}
		if (value.isString()) {
			try {
				return Double.parseDouble(value.asString());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private Integer intOrNull(JsonNode node, String field) {
		JsonNode value = node.path(field);
		if (value.isNumber()) {
			return value.asInt();
		}
		if (value.isString()) {
			try {
				return Integer.parseInt(value.asString());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** 세트 메뉴는 price 가 "" 로 온다 — 빈 문자열은 값 없음으로 통일 */
	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private String textOrNull(JsonNode node, String fieldName) {
		return textOrSelf(node.path(fieldName));
	}

	private String textOrSelf(JsonNode field) {
		return field.isString() || field.isNumber() ? field.asString() : null;
	}
}
