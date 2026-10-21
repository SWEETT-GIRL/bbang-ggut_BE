package sweetgirl.dataops.snapshot.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import sweetgirl.dataops.snapshot.service.ResponseDigests;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/**
 * 선언형 구조 별칭만 담는 프로필. 대상 확인, 완전성, 가격 의미와 승인 규칙은 이 파일 밖의
 * 보호된 코드에 남는다. 기존 매핑은 삭제/재해석할 수 없고 새 별칭만 검증·승인하여 추가한다.
 */
@Component
public final class MenuStructureProfile {

	public static final String RESOURCE = "/response-agent/menu-structure-profile-v1.json";
	public enum PriceShape { SCALAR, DISPLAY_TEXT_WON }
	public enum ImageShape { STRING, URL_OBJECT }
	public record Entity(String typename, PriceShape priceShape, Set<ImageShape> imageShapes) {
		public Entity { imageShapes = Set.copyOf(imageShapes); }
		public boolean acceptsPrice(JsonNode value) {
			return priceShape == PriceShape.SCALAR ? value.isString() || value.isNumber()
				: value.isObject() && value.path("displayText").isString();
		}
		public boolean acceptsImage(JsonNode value) {
			return imageShapes.contains(ImageShape.STRING) && value.isString()
				|| imageShapes.contains(ImageShape.URL_OBJECT) && value.isObject() && value.path("url").isString();
		}
	}
	public record Container(String field, String itemsField, List<String> totalFields) {
		public Container { totalFields = List.copyOf(totalFields); }
		public JsonNode items(JsonNode node) { return node.isArray() ? node : node.path(itemsField); }
		public JsonNode declaredTotal(JsonNode node) {
			for (String field : totalFields) {
				if (node.path(field).isIntegralNumber()) return node.path(field);
			}
			return MissingNode.getInstance();
		}
	}

	private static final Set<String> RESERVED = Set.of("id", "name", "price", "images", "description",
		"recommend", "base", "errors", "hasNextPage", "pageInfo", "newBusinessHours", "coordinate");
	private final String version;
	private final String digest;
	private final Map<String, Entity> entities;
	private final Map<String, Container> containers;

	public MenuStructureProfile() { this(readBundled()); }

	private MenuStructureProfile(JsonNode json) {
		exactFields(json, Set.of("schemaVersion", "version", "entities", "containers"));
		if (!json.path("schemaVersion").isIntegralNumber() || json.path("schemaVersion").asInt() != 1)
			throw invalid("schemaVersion");
		version = requiredText(json.path("version"));
		if (!version.matches("[a-z][a-z0-9-]{0,63}")) throw invalid("version");
		Map<String, Entity> entityMap = new LinkedHashMap<>();
		for (JsonNode item : boundedArray(json.path("entities"), 16)) {
			exactFields(item, Set.of("typename", "priceShape", "imageShapes"));
			String typename = identifier(item.path("typename"));
			PriceShape priceShape;
			try { priceShape = PriceShape.valueOf(requiredText(item.path("priceShape"))); }
			catch (IllegalArgumentException e) { throw invalid("priceShape"); }
			Set<ImageShape> shapes = new java.util.HashSet<>();
			for (JsonNode shape : boundedArray(item.path("imageShapes"), 2)) {
				try {
					if (!shapes.add(ImageShape.valueOf(requiredText(shape)))) throw invalid("duplicate imageShape");
				} catch (IllegalArgumentException e) { throw invalid("imageShape"); }
			}
			if (entityMap.putIfAbsent(typename, new Entity(typename, priceShape, shapes)) != null)
				throw invalid("duplicate typename");
		}
		Map<String, Container> containerMap = new LinkedHashMap<>();
		for (JsonNode item : boundedArray(json.path("containers"), 8)) {
			exactFields(item, Set.of("field", "itemsField", "totalFields"));
			String field = structuralField(item.path("field"));
			String itemsField = structuralField(item.path("itemsField"));
			List<String> totals = new ArrayList<>();
			for (JsonNode total : boundedArray(item.path("totalFields"), 4)) {
				String name = structuralField(total);
				if (name.equals(itemsField) || totals.contains(name)) throw invalid("ambiguous totalField");
				totals.add(name);
			}
			if (containerMap.putIfAbsent(field, new Container(field, itemsField, totals)) != null)
				throw invalid("duplicate container");
		}
		// A candidate cannot silently turn the known object price into a scalar or remove old support.
		requireEntity(entityMap, "Menu", PriceShape.SCALAR);
		requireEntity(entityMap, "PlaceMenuItem", PriceShape.DISPLAY_TEXT_WON);
		for (String field : List.of("menus", "placeMenus")) {
			if (!new Container(field, "items", List.of("total", "totalCount", "menuCount")).equals(containerMap.get(field)))
				throw invalid("protected container mapping: " + field);
		}
		entities = Map.copyOf(entityMap);
		containers = Map.copyOf(containerMap);
		digest = ResponseDigests.sha256(ResponseDigests.canonical(json, new ObjectMapper()));
	}

	public static MenuStructureProfile bundled() { return Bundled.PROFILE; }
	private static final class Bundled { private static final MenuStructureProfile PROFILE = new MenuStructureProfile(); }
	public static MenuStructureProfile fromJson(JsonNode json) { return new MenuStructureProfile(json); }
	public static MenuStructureProfile fromJson(String json) { return fromJson(new ObjectMapper().readTree(json)); }
	public String version() { return version; }
	public String digest() { return digest; }
	public Entity entity(String typename) { return typename == null ? null : entities.get(typename); }
	public Container container(String queryField) {
		int arguments = queryField.indexOf('(');
		return containers.get(arguments < 0 ? queryField : queryField.substring(0, arguments));
	}

	private static JsonNode readBundled() {
		try (var input = MenuStructureProfile.class.getResourceAsStream(RESOURCE)) {
			if (input == null) throw new IllegalStateException("Menu structure profile missing");
			return new ObjectMapper().readTree(input.readAllBytes());
		} catch (IOException e) { throw new IllegalStateException("Menu structure profile unreadable", e); }
	}
	private static void requireEntity(Map<String, Entity> entities, String typename, PriceShape shape) {
		if (!new Entity(typename, shape, Set.of(ImageShape.STRING, ImageShape.URL_OBJECT)).equals(entities.get(typename)))
			throw invalid("protected entity mapping: " + typename);
	}
	private static JsonNode boundedArray(JsonNode node, int max) {
		if (!node.isArray() || node.isEmpty() || node.size() > max) throw invalid("array bounds");
		return node;
	}
	private static String requiredText(JsonNode node) {
		if (!node.isString() || node.asString().isBlank()) throw invalid("text");
		return node.asString();
	}
	private static String identifier(JsonNode node) {
		String value = requiredText(node);
		if (!value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw invalid("identifier");
		return value;
	}
	private static String structuralField(JsonNode node) {
		String value = identifier(node);
		if (RESERVED.contains(value)) throw invalid("semantic field remapping");
		return value;
	}
	private static void exactFields(JsonNode node, Set<String> fields) {
		if (!node.isObject() || node.size() != fields.size()
			|| node.properties().stream().anyMatch(e -> !fields.contains(e.getKey()))) throw invalid("unknown/missing field");
	}
	private static IllegalArgumentException invalid(String reason) {
		return new IllegalArgumentException("Invalid menu structure profile: " + reason);
	}
}
