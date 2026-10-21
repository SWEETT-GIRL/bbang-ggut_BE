package sweetgirl.dataops.snapshot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class ResponseDigests {
	private ResponseDigests() {}
	public static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
	public static String canonical(JsonNode node, ObjectMapper mapper) { return mapper.writeValueAsString(sorted(node)); }
	private static Object sorted(JsonNode node) {
		if (node.isObject()) {
			var fields = new TreeMap<String, Object>();
			node.properties().forEach(e -> fields.put(e.getKey(), sorted(e.getValue())));
			return fields;
		}
		if (node.isArray()) {
			var values = new ArrayList<Object>();
			node.forEach(v -> values.add(sorted(v)));
			return values;
		}
		return node;
	}
	public static String classes(Class<?>... types) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			for (Class<?> type : types) {
				try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
					if (input == null) throw new IllegalStateException("Parser class unavailable");
					digest.update(input.readAllBytes());
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
	}
}
