package sweetgirl.dataops.snapshot.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 네이버 플레이스 __APOLLO_STATE__ (정규화 캐시) 파서.
 * Apollo 는 엔티티 간 참조를 {"__ref": "키"} 로 저장하므로 반드시 해소해야 한다 —
 * 참조 미해소가 has_hours 파싱 실패(리서치 2.3, 399/400 유실)의 원인이었다.
 */
@Component
public class NaverPlaceParser {

	private static final String REF_FIELD = "__ref";
	private static final String TYPENAME_FIELD = "__typename";
	private static final String BASE_KEY_PREFIX = "PlaceDetailBase";
	private static final Pattern PLACE_MENU_PRICE_PATTERN = Pattern.compile("^([0-9][0-9,]*)\\s*원$");
	private static final String BAEMIN_MENU_TYPENAME = "PlaceDetail_BaeminMenu";
	private static final String BAEMIN_GROUP_TYPENAME = "PlaceDetail_BaeminMenuGroup";
	private static final String BAEMIN_REPRESENTATIVE_GROUP = "대표메뉴";
	private static final int MAX_REF_DEPTH = 5;
	private static final Pattern INSTAGRAM_PATTERN = Pattern.compile("instagram\\.com/([A-Za-z0-9._]+)");

	private final MenuStructureProfile structureProfile;

	public NaverPlaceParser() { this(MenuStructureProfile.bundled()); }

	@Autowired
	public NaverPlaceParser(MenuStructureProfile structureProfile) {
		this.structureProfile = java.util.Objects.requireNonNull(structureProfile);
	}

	public MenuStructureProfile structureProfile() { return structureProfile; }

	public ParsedPlace parse(JsonNode apolloState) {
		JsonNode base = findBaseNode(apolloState);
		JsonNode hoursRoot = findNewBusinessHoursNode(apolloState);
		JsonNode coordinate = base.path("coordinate");
		return new ParsedPlace(
			textOrNull(base, "name"),
			textOrNull(base, "phone"),
			textOrNull(base, "address"),
			textOrNull(base, "roadAddress"),
			doubleOrNull(coordinate, "x"),
			doubleOrNull(coordinate, "y"),
			parseBusinessStatus(hoursRoot),
			parseBusinessHours(hoursRoot),
			findInstagramHandle(apolloState),
			parseMenus(apolloState),
			parseBaeminMenus(apolloState));
	}

	/** 영업시간 원문(참조 해소된 JSON 텍스트)만 — 가게 신호 파서가 같은 위치 규칙을 재사용한다 */
	public String parseBusinessHoursRaw(JsonNode apolloState) {
		return parseBusinessHours(findNewBusinessHoursNode(apolloState));
	}

	/** coordinate.x(경도)/y(위도)는 문자열로 온다 — 숫자 변환 실패 시 null */
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

	private JsonNode findBaseNode(JsonNode apolloState) {
		for (Map.Entry<String, JsonNode> entry : apolloState.properties()) {
			if (entry.getKey().startsWith(BASE_KEY_PREFIX)) {
				return entry.getValue();
			}
		}
		return MissingNode.getInstance();
	}

	/**
	 * 영업시간·영업상태의 실제 위치 (표본 1,000 실측):
	 * ROOT_QUERY.placeDetail({"input":...}).newBusinessHours({"format":"restaurant"})[0]
	 * 2026-08 말부터는 인자 없는 "newBusinessHours" 키로도 온다(09-04 실측 최근 300건 중 267건) — 접두 "newBusinessHours" 로 둘 다 잡는다.
	 * — ROOT_QUERY 필드 키에 GraphQL 인자가 포함되므로 접두 매칭으로 찾는다.
	 * PlaceDetailBase.openingHours 는 전부 null — 쓰지 않는다.
	 */
	private JsonNode findNewBusinessHoursNode(JsonNode apolloState) {
		JsonNode placeDetail = resolveDeep(
			apolloState, findFieldByPrefix(apolloState.path("ROOT_QUERY"), "placeDetail("), 0);
		JsonNode hoursContainer = resolveDeep(
			apolloState, findFieldByPrefix(placeDetail, "newBusinessHours"), 0);
		return hoursContainer.path(0);
	}

	private String parseBusinessStatus(JsonNode hoursRoot) {
		JsonNode statusDescription = hoursRoot.path("businessStatusDescription");
		if (statusDescription.isMissingNode() || statusDescription.isNull()) {
			return null;
		}
		String status = textOrNull(statusDescription, "status");
		return status != null ? status : textOrSelf(statusDescription);
	}

	private String parseBusinessHours(JsonNode hoursRoot) {
		JsonNode hours = hoursRoot.path("businessHours");
		if (hours.isMissingNode() || hours.isNull()
			|| ((hours.isObject() || hours.isArray()) && hours.isEmpty())) {
			return null;
		}
		return hours.toString();
	}

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

	private List<ParsedMenu> parseMenus(JsonNode apolloState) {
		List<ParsedMenu> menus = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : apolloState.properties()) {
			JsonNode candidate = entry.getValue();
			String typename = textOrNull(candidate, TYPENAME_FIELD);
			MenuStructureProfile.Entity entity = structureProfile.entity(typename);
			if (entity != null) {
				menus.add(new ParsedMenu(
					textOrNull(candidate, "name"),
					entity.priceShape() == MenuStructureProfile.PriceShape.SCALAR
						? textOrNull(candidate, "price") : placeMenuPrice(candidate.path("price")),
					textOrNull(candidate, "description"),
					parseImageUrls(candidate.path("images"), entity),
					null));
			}
		}
		return menus;
	}

	/** "5,300원" → "5300" (옛 Menu.price 와 같은 숫자 문자열). 변동가·범위·문의처럼 한 값이 아니면 null — 가격 제안을 내지 않는다 */
	private String placeMenuPrice(JsonNode price) {
		String displayText = textOrNull(price, "displayText");
		if (displayText == null) {
			return null;
		}
		Matcher matcher = PLACE_MENU_PRICE_PATTERN.matcher(displayText.strip());
		return matcher.matches() ? matcher.group(1).replace(",", "") : null;
	}

	/** Read only menus reachable from this place's Baemin root, never unrelated Apollo cache entries. */
	private List<ParsedMenu> parseBaeminMenus(JsonNode apolloState) {
		JsonNode root = resolveReference(apolloState, findFieldByPrefix(apolloState.path("ROOT_QUERY"), "placeDetail("));
		JsonNode baemin = resolveReference(apolloState, root.path("baemin"));
		Map<String, String> groupNames = mapBaeminGroups(apolloState, baemin);
		List<ParsedMenu> menus = new ArrayList<>();
		JsonNode items = resolveReference(apolloState, baemin.path("menus"));
		if (!items.isArray()) return menus;
		for (JsonNode item : items) {
			JsonNode menu = resolveReference(apolloState, item);
			if (!BAEMIN_MENU_TYPENAME.equals(textOrNull(menu, TYPENAME_FIELD))) continue;
			menus.add(new ParsedMenu(textOrNull(menu, "name"), textOrNull(menu, "price"),
				textOrNull(menu, "desc"), parseImageUrls(menu.path("images")),
				groupNames.get(textOrNull(menu, "id"))));
		}
		return menus;
	}

	private Map<String, String> mapBaeminGroups(JsonNode payload, JsonNode baemin) {
		Map<String, String> names = new HashMap<>();
		JsonNode groups = resolveReference(payload, baemin.path("menuGroups"));
		if (!groups.isArray()) return names;
		for (JsonNode reference : groups) {
			JsonNode group = resolveReference(payload, reference);
			if (!BAEMIN_GROUP_TYPENAME.equals(textOrNull(group, TYPENAME_FIELD))) continue;
			String name = textOrNull(group, "name");
			JsonNode items = resolveReference(payload, group.path("menus"));
			if (!items.isArray()) continue;
			for (JsonNode item : items) {
				String id = textOrNull(resolveReference(payload, item), "id");
				if (id != null && (names.get(id) == null || BAEMIN_REPRESENTATIVE_GROUP.equals(names.get(id)))) {
					names.put(id, name);
				}
			}
		}
		return names;
	}

	private JsonNode resolveReference(JsonNode payload, JsonNode node) {
		var seen = new java.util.HashSet<String>();
		while (node.isObject() && node.has(REF_FIELD)) {
			String ref = node.path(REF_FIELD).asString("");
			if (seen.size() >= 8 || !seen.add(ref) || !payload.has(ref)) return MissingNode.getInstance();
			node = payload.path(ref);
		}
		return node;
	}

	private List<String> parseImageUrls(JsonNode images, MenuStructureProfile.Entity entity) {
		List<String> urls = new ArrayList<>();
		if (images.isArray()) for (JsonNode image : images) {
			if (entity.acceptsImage(image)) urls.add(image.isString() ? image.asString() : image.path("url").asString());
		}
		return urls;
	}

	private List<String> parseImageUrls(JsonNode images) {
		List<String> imageUrls = new ArrayList<>();
		if (images.isArray()) {
			images.forEach(image -> {
				if (image.isString()) {
					imageUrls.add(image.asString());
				} else if (image.path("url").isString()) {
					imageUrls.add(image.path("url").asString());
				}
			});
		}
		return imageUrls;
	}

	/** 인스타 핸들은 홈페이지 구조화 필드에 URL 로 박혀 온다 (리서치 2.4) — 위치 가정 없이 전체 텍스트에서 추출 */
	private String findInstagramHandle(JsonNode apolloState) {
		Matcher matcher = INSTAGRAM_PATTERN.matcher(apolloState.toString());
		if (matcher.find()) {
			String handle = matcher.group(1);
			return "p".equals(handle) || "reel".equals(handle) || "explore".equals(handle) ? null : handle;
		}
		return null;
	}

	/** {"__ref": "키"} 참조를 재귀 해소 — 순환 참조 방어로 깊이 제한 */
	private JsonNode resolveDeep(JsonNode apolloState, JsonNode node, int depth) {
		if (depth >= MAX_REF_DEPTH || node == null) {
			return node;
		}
		if (node.isObject() && node.has(REF_FIELD)) {
			return resolveDeep(apolloState, apolloState.path(node.get(REF_FIELD).asString()), depth + 1);
		}
		if (node.isObject()) {
			ObjectNode resolved = JsonNodeFactory.instance.objectNode();
			for (Map.Entry<String, JsonNode> entry : node.properties()) {
				resolved.set(entry.getKey(), resolveDeep(apolloState, entry.getValue(), depth + 1));
			}
			return resolved;
		}
		if (node.isArray()) {
			ArrayNode resolved = JsonNodeFactory.instance.arrayNode();
			node.forEach(element -> resolved.add(resolveDeep(apolloState, element, depth + 1)));
			return resolved;
		}
		return node;
	}

	private String textOrNull(JsonNode node, String fieldName) {
		return textOrSelf(node.path(fieldName));
	}

	private String textOrSelf(JsonNode field) {
		return field.isString() || field.isNumber() ? field.asString() : null;
	}
}
