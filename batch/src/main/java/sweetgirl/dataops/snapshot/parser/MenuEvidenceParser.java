package sweetgirl.dataops.snapshot.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/** Absence needs a complete, target-scoped list, not merely Menu entities somewhere in Apollo's cache. */
@Component
public class MenuEvidenceParser {

	/**
	 * 파서 세대 — 완전성 판정 규칙이 바뀌면 올린다. 관측 행에 함께 저장되므로 세대가 다른 관측을
	 * 같은 기준으로 세지 않는다.
	 *
	 * v2 (2026-09-26): 목록 전체 수의 키로 {@code menuCount} 를 인정한다.
	 * 네이버가 09-06~09-20 사이에 메뉴 payload 모양을 바꿨다 — 옛 모양은 루트에 {@code menus} 맨 배열이라
	 * 개수가 없었고, 새 모양은 {@code placeMenus} 컨테이너에 {@code menuCount} 를 준다.
	 * v1 은 {@code total}/{@code totalCount} 만 찾아서, 새 모양에서도 완전성이 영영 서지 않았다
	 * (저장된 원본 실측: 09-20 이후 수집분은 100% placeMenus 이고 menuCount == items 가 2,441/2,441,
	 *  메뉴 140개인 가게까지 반례 없음).
	 */
	public static final String VERSION = "naver-menu-root-v2";

	private final ObjectMapper objectMapper;
	private final MenuStructureProfile structureProfile;

	public MenuEvidenceParser(ObjectMapper objectMapper) { this(objectMapper, MenuStructureProfile.bundled()); }

	@Autowired
	public MenuEvidenceParser(ObjectMapper objectMapper, MenuStructureProfile structureProfile) {
		this.objectMapper = objectMapper;
		this.structureProfile = java.util.Objects.requireNonNull(structureProfile);
	}

	public MenuStructureProfile structureProfile() { return structureProfile; }
	public String version() { return VERSION + ":" + structureProfile.digest(); }

	public Evidence parse(JsonNode payload, String externalId) {
		if (externalId == null || externalId.isBlank() || !payload.isObject()) {
			return Evidence.unknown();
		}
		List<JsonNode> matchingRoots = new ArrayList<>();
		for (Map.Entry<String, JsonNode> entry : payload.path("ROOT_QUERY").properties()) {
			if (externalId.equals(rootId(entry.getKey()))) {
				matchingRoots.add(resolve(payload, entry.getValue()));
			}
		}
		if (matchingRoots.size() != 1 || !matchingRoots.get(0).isObject()) {
			return Evidence.unknown();
		}
		JsonNode root = matchingRoots.get(0);
		String declaredId = text(root.path("id"));
		if (declaredId != null && !externalId.equals(declaredId)) {
			return Evidence.unknown();
		}
		// A base reference is optional, but a contradictory one must never authenticate this root.
		JsonNode base = resolve(payload, root.path("base"));
		String baseId = text(base.path("id"));
		if (baseId != null && !externalId.equals(baseId)) {
			return Evidence.unknown();
		}
		List<Map.Entry<MenuStructureProfile.Container, JsonNode>> containers = new ArrayList<>();
		boolean unfilteredList = true;
		for (Map.Entry<String, JsonNode> entry : root.properties()) {
			MenuStructureProfile.Container mapping = structureProfile.container(entry.getKey());
			if (mapping != null) {
				containers.add(Map.entry(mapping, resolve(payload, entry.getValue())));
				// Unknown query arguments may request one category/page even when that subset's total matches.
				unfilteredList &= !entry.getKey().contains("(");
			}
		}
		Set<String> names = new TreeSet<>();
		Set<String> representatives = new TreeSet<>();
		boolean complete = containers.size() == 1 && unfilteredList;
		for (var entry : containers) {
			JsonNode container = entry.getValue();
			JsonNode items = resolve(payload, entry.getKey().items(container));
			if (!items.isArray()) {
				complete = false;
				continue;
			}
			// 목록 전체 수의 키 이름은 payload 세대마다 다르다 — 어느 이름이든 "이 목록이 전부"라는 같은 주장이다
			JsonNode total = entry.getKey().declaredTotal(container);
			complete &= total.isIntegralNumber() && total.asLong(-1) == items.size()
				&& !container.path("hasNextPage").asBoolean(false)
				&& !resolve(payload, container.path("pageInfo")).path("hasNextPage").asBoolean(false)
				&& container.path("errors").isMissingNode();
			Set<String> itemIds = new HashSet<>();
			for (JsonNode item : items) {
				JsonNode menu = resolve(payload, item);
				String name = text(menu.path("name"));
				String type = text(menu.path("__typename"));
				if (name == null || structureProfile.entity(type) == null) {
					complete = false;
					continue;
				}
				String menuId = text(menu.path("id"));
				if (menuId == null || !itemIds.add(menuId)) {
					complete = false;
				}
				names.add(name);
				if (menu.path("recommend").isBoolean() && menu.path("recommend").asBoolean()) {
					representatives.add(name);
				}
			}
		}
		if (payload.has("errors") && !payload.path("errors").isEmpty()) {
			complete = false;
		}
		return new Evidence(List.copyOf(names), List.copyOf(representatives), complete, true);
	}

	private String rootId(String key) {
		if (!key.startsWith("placeDetail(") || !key.endsWith(")")) {
			return null;
		}
		try {
			JsonNode arguments = objectMapper.readTree(key.substring("placeDetail(".length(), key.length() - 1));
			return text(arguments.has("input") ? arguments.path("input").path("id") : arguments.path("id"));
		} catch (RuntimeException malformedArguments) {
			return null;
		}
	}

	private JsonNode resolve(JsonNode payload, JsonNode node) {
		Set<String> visited = new HashSet<>();
		for (int depth = 0; depth < 8 && node.has("__ref"); depth++) {
			String ref = text(node.path("__ref"));
			if (ref == null || !visited.add(ref)) {
				return MissingNode.getInstance();
			}
			node = payload.path(ref);
		}
		return node.has("__ref") ? MissingNode.getInstance() : node;
	}

	private String text(JsonNode node) {
		return node.isString() && !node.asString().isBlank() ? node.asString().strip()
			: node.isIntegralNumber() ? node.asString() : null;
	}

	public record Evidence(List<String> menuNames, List<String> representativeNames, boolean menuComplete,
			boolean identityVerified) {
		public static Evidence unknown() {
			return new Evidence(List.of(), List.of(), false, false);
		}
	}
}
