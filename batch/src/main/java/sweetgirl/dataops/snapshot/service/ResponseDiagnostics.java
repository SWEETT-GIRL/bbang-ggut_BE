package sweetgirl.dataops.snapshot.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sweetgirl.dataops.snapshot.dto.request.SnapshotSubmitRequest;
import sweetgirl.dataops.snapshot.parser.BusinessHoursParser;
import sweetgirl.dataops.snapshot.parser.MenuEvidenceParser;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.MenuStructureProfile;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;

/** Only menu completeness uncertainty permits store/hours processing; all other issues hold the observation. */
@Component
public class ResponseDiagnostics {
	public static final String CONTRACT = "naver-response-v1";
	public static final String VERSION = "menu-scope-v3-profile";
	private final ObjectMapper mapper;
	private final NaverPlaceParser parser;
	private final BusinessHoursParser hoursParser;
	private final MenuEvidenceParser evidenceParser;
	private final MenuStructureProfile structureProfile;

	public ResponseDiagnostics(ObjectMapper mapper, NaverPlaceParser parser, BusinessHoursParser hoursParser,
			MenuEvidenceParser evidenceParser) {
		this(mapper, parser, hoursParser, evidenceParser, parser.structureProfile());
	}

	@Autowired
	public ResponseDiagnostics(ObjectMapper mapper, NaverPlaceParser parser, BusinessHoursParser hoursParser,
			MenuEvidenceParser evidenceParser, MenuStructureProfile structureProfile) {
		this.mapper = mapper;
		this.parser = parser;
		this.hoursParser = hoursParser;
		this.evidenceParser = evidenceParser;
		this.structureProfile = java.util.Objects.requireNonNull(structureProfile);
		if (!structureProfile.digest().equals(parser.structureProfile().digest())
			|| !structureProfile.digest().equals(evidenceParser.structureProfile().digest()))
			throw new IllegalArgumentException("Menu structure profiles disagree");
	}

	public MenuStructureProfile structureProfile() { return structureProfile; }

	/** A JSON-only profile repair is a new runtime parser, even when the class bytes are unchanged. */
	public String runtimeParserDigest() {
		return ResponseDigests.sha256("response-parser-profile-v1\n" + ResponseDigests.classes(
			NaverPlaceParser.class, BusinessHoursParser.class, MenuEvidenceParser.class, ParsedPlace.class,
			ResponseDiagnostics.class, MenuStructureProfile.class, MenuStructureProfile.Entity.class,
			MenuStructureProfile.Container.class, MenuStructureProfile.PriceShape.class, MenuStructureProfile.ImageShape.class)
			+ "\n" + structureProfile.digest());
	}
	public record Issue(String path, String code, String actualType, boolean modelEligible) {}
	public record Report(String parserDigest, List<Issue> issues) {
		public boolean held() { return issues.stream().anyMatch(i -> !"MENU_INCOMPLETE".equals(i.code())); }
		public boolean menuAllowed() { return issues.isEmpty(); }
		public String assessment() { return held() ? "HELD" : menuAllowed() ? "READY" : "PARTIAL"; }
		public boolean targetVerified() {
			return issues.stream().noneMatch(i -> Set.of("UNVERIFIED_INPUT", "ROOT_UNKNOWN",
				"ARGUMENTS_UNSUPPORTED", "ROOT_TYPE_CHANGED", "AMBIGUOUS_BASE", "IDENTITY_UNKNOWN",
				"PARSER_EXCEPTION", "PARSER_INPUT_MUTATION").contains(i.code()));
		}
		public boolean modelEligible() { return issues.stream().anyMatch(Issue::modelEligible); }
	}

	public Report inspect(SnapshotSubmitRequest request) {
		List<Issue> issues = new ArrayList<>();
		String digest = runtimeParserDigest();
		try { inspect(request, issues); }
		catch (RuntimeException e) {
			boolean mutated = "PARSER_INPUT_MUTATION".equals(e.getMessage());
			issues.add(new Issue("$", mutated ? "PARSER_INPUT_MUTATION" : "PARSER_EXCEPTION", e.getClass().getSimpleName(), !mutated));
		}
		return new Report(digest, List.copyOf(issues));
	}

	/** Candidate parsers may normalize their own copy, but must not mutate their caller's input. */
	public static ParsedPlace parsePreservingInput(NaverPlaceParser parser, JsonNode payload) {
		JsonNode isolated = payload.deepCopy();
		try { return parser.parse(isolated); }
		finally {
			if (!isolated.equals(payload)) throw new IllegalStateException("PARSER_INPUT_MUTATION");
		}
	}

	private void inspect(SnapshotSubmitRequest request, List<Issue> issues) {
		JsonNode payload = request.payload();
		if (!payload.isObject() || request.externalId() == null || request.externalId().isBlank()
			|| request.observationId() == null || request.observationId().isBlank() || request.observedAt() == null
			|| request.observedAt().toInstant().isBefore(java.time.Instant.parse("1970-01-01T00:00:00Z"))
			|| request.observedAt().toInstant().isAfter(java.time.Instant.now().plusSeconds(300))
			|| !"NAVER".equals(request.source().name())) {
			issues.add(new Issue("$", "UNVERIFIED_INPUT", type(payload), false));
			return;
		}
		if (payload.has("errors") && !payload.path("errors").isEmpty()) {
			issues.add(new Issue("$.errors", "PARTIAL_RESPONSE", type(payload.path("errors")), false));
		}
		List<JsonNode> roots = new ArrayList<>();
		int allRoots = 0;
		for (var field : payload.path("ROOT_QUERY").properties()) {
			String key = field.getKey();
			if (!key.startsWith("placeDetail(") || !key.endsWith(")")) continue;
			allRoots++;
			try {
				JsonNode args = mapper.readTree(key.substring(12, key.length() - 1));
				String id = (args.has("input") ? args.path("input") : args).path("id").asString("");
				if (request.externalId().equals(id)) roots.add(resolve(payload, field.getValue(), issues));
			} catch (RuntimeException e) { issues.add(new Issue("placeDetail", "ARGUMENTS_UNSUPPORTED", "key", true)); }
		}
		// NaverPlaceParser currently chooses the first root: do not accept ambiguous cache contents.
		if (roots.size() != 1 || allRoots != 1) {
			issues.add(new Issue("placeDetail", "ROOT_UNKNOWN", "ambiguous-or-missing", true));
			return;
		}
		JsonNode root = roots.get(0);
		if (root.isNull()) {
			issues.add(new Issue("placeDetail", "EXPLICIT_PAGE_ABSENT", "null", false));
			return;
		}
		if (!root.isObject()) { issues.add(new Issue("placeDetail", "ROOT_TYPE_CHANGED", type(root), true)); return; }
		ParsedPlace parsed = parsePreservingInput(parser, payload);
		if (parsed.name() == null || parsed.name().isBlank()) {
			issues.add(new Issue("PlaceDetailBase.name", "REQUIRED_FIELD_UNREADABLE", "missing-output", true));
		}
		var bases = payload.properties().stream().filter(e -> e.getKey().startsWith("PlaceDetailBase")).toList();
		if (bases.size() != 1) issues.add(new Issue("PlaceDetailBase", "AMBIGUOUS_BASE", "ambiguous-or-missing", false));
		for (var base : bases) {
			String declaredId = base.getValue().path("id").asString(null);
			if (declaredId != null && !request.externalId().equals(declaredId)) issues.add(new Issue("PlaceDetailBase.id", "IDENTITY_UNKNOWN", "mismatch", false));
			for (String name : List.of("phone", "address", "roadAddress")) {
				JsonNode value = base.getValue().path(name);
				if (!value.isString() && !value.isNumber() && !value.isNull() && !value.isMissingNode()) issues.add(new Issue("PlaceDetailBase." + name, "FIELD_TYPE_UNSUPPORTED", type(value), true));
			}
		}
		var evidence = evidenceParser.parse(payload, request.externalId());
		if (!evidence.identityVerified()) issues.add(new Issue("placeDetail.id", "IDENTITY_UNKNOWN", "unknown", false));
		if (!evidence.menuComplete()) issues.add(new Issue("placeDetail.menus", "MENU_INCOMPLETE", "unknown", false));
		Set<String> names = new TreeSet<>();
		parsed.menus().forEach(m -> { if (m.name() != null) names.add(m.name()); });
		if (!names.equals(new TreeSet<>(evidence.menuNames()))) issues.add(new Issue("menus", "MENU_OUTPUT_MISMATCH", "names", true));
		int hoursFields = 0;
		for (var field : root.properties()) {
			String key = field.getKey();
			MenuStructureProfile.Container mapping = structureProfile.container(key);
			if (mapping != null) {
				JsonNode container = resolve(payload, field.getValue(), issues);
				// Known null/missing data cannot prove completeness, but is not an unfamiliar shape.
				// MENU_INCOMPLETE restricts menu processing without requesting an LLM fix.
				if (container.isNull() || container.isMissingNode()) continue;
				JsonNode items = resolve(payload, mapping.items(container), issues);
				if (!items.isArray()) issues.add(new Issue("menus.items", "MENU_CONTAINER_UNSUPPORTED", type(items), true));
				else for (JsonNode item : items) inspectMenu(resolve(payload, item, issues), issues);
			}
			if (field(key, "newBusinessHours")) {
				hoursFields++;
				JsonNode hours = resolve(payload, field.getValue(), issues);
				if (!hours.isNull() && !hours.isArray()) issues.add(new Issue("newBusinessHours", "HOURS_CONTAINER_UNSUPPORTED", type(hours), true));
				if (hours.isArray() && hours.size() > 1) issues.add(new Issue("newBusinessHours", "MULTIPLE_HOURS_BLOCKS", "array", false));
				if (hours.isArray() && !hours.isEmpty()) {
					JsonNode first = resolve(payload, hours.path(0), issues);
					JsonNode raw = resolve(payload, first.path("businessHours"), issues);
					if (!raw.isNull() && !raw.isMissingNode() && !raw.isEmpty() && parsed.businessHoursRaw() == null) issues.add(new Issue("businessHours", "HOURS_OUTPUT_EMPTY", type(raw), true));
				}
			}
		}
		if (hoursFields > 1) issues.add(new Issue("newBusinessHours", "AMBIGUOUS_HOURS", "multiple", false));
		inspectHours(parsed.businessHoursRaw(), issues);
	}

	private void inspectMenu(JsonNode menu, List<Issue> issues) {
		String typename = menu.path("__typename").asString("");
		MenuStructureProfile.Entity entity = structureProfile.entity(typename);
		if (entity == null) issues.add(new Issue("menus[].__typename", "MENU_ENTITY_UNSUPPORTED", typename, true));
		if (!menu.path("name").isString() || menu.path("name").asString().isBlank()) issues.add(new Issue("menus[].name", "MENU_NAME_UNREADABLE", type(menu.path("name")), true));
		JsonNode price = menu.path("price");
		if (!price.isNull() && !price.isMissingNode()) {
			boolean supported = entity != null && entity.acceptsPrice(price);
			if (!supported) issues.add(new Issue("menus[].price", "PRICE_SHAPE_UNSUPPORTED", type(price), true));
		}
		JsonNode images = menu.path("images");
		if (!images.isArray() && !images.isNull() && !images.isMissingNode()) issues.add(new Issue("menus[].images", "IMAGE_SHAPE_UNSUPPORTED", type(images), true));
		if (images.isArray()) for (JsonNode image : images) {
			if (entity == null || !entity.acceptsImage(image)) issues.add(new Issue("menus[].images[]", "IMAGE_SHAPE_UNSUPPORTED", type(image), true));
		}
	}

	private void inspectHours(String raw, List<Issue> issues) {
		if (raw == null) return;
		JsonNode hours = mapper.readTree(raw);
		if (!hours.isArray()) { issues.add(new Issue("businessHours", "HOURS_SHAPE_UNSUPPORTED", type(hours), true)); return; }
		for (JsonNode entry : hours) {
			String day = entry.path("day").asString("");
			if (day.matches("[월화수목금토일]\\([^)]*\\)")) continue; // Existing dated-exception policy.
			if (!Set.of("월", "화", "수", "목", "금", "토", "일", "매일").contains(day)) { issues.add(new Issue("businessHours[].day", "DAY_UNSUPPORTED", type(entry.path("day")), true)); continue; }
			var rows = hoursParser.parse("[" + entry + "]");
			if (rows.isEmpty()) issues.add(new Issue("businessHours[]", "HOURS_MEANING_UNKNOWN", "no-output", false));
			for (var row : rows) if (!row.closed() && (!validTime(row.openTime()) || !validTime(row.closeTime()))) issues.add(new Issue("businessHours[].time", "TIME_UNSUPPORTED", "string", true));
		}
	}
	private boolean validTime(String time) { return time != null && time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"); }
	private boolean field(String key, String name) { return key.equals(name) || key.startsWith(name + "("); }
	private String type(JsonNode node) { return node.getNodeType().name(); }
	private JsonNode resolve(JsonNode payload, JsonNode node, List<Issue> issues) {
		Set<String> seen = new HashSet<>();
		while (node.isObject() && node.has("__ref")) {
			String ref = node.path("__ref").asString("");
			if (seen.size() >= 8 || !seen.add(ref) || !payload.has(ref)) { issues.add(new Issue("__ref", "UNRESOLVED_REFERENCE", "reference", false)); return MissingNode.getInstance(); }
			node = payload.path(ref);
		}
		return node;
	}
}
