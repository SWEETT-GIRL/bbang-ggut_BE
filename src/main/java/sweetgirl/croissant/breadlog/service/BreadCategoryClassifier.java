package sweetgirl.croissant.breadlog.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class BreadCategoryClassifier {

	private static final String CATEGORY_FILE = "bread-record-categories.txt";
	private static final String FALLBACK_CATEGORY = "기타";

	private final List<String> categories;

	public BreadCategoryClassifier() {
		this.categories = loadCategories();
	}

	public String classify(String breadName) {
		String normalizedName = normalize(breadName);
		if (normalizedName.isEmpty()) {
			return FALLBACK_CATEGORY;
		}
		return categories.stream()
			.filter(normalizedName::contains)
			.findFirst()
			.orElse(FALLBACK_CATEGORY);
	}

	private List<String> loadCategories() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			new ClassPathResource(CATEGORY_FILE).getInputStream(), StandardCharsets.UTF_8))) {
			return reader.lines()
				.map(String::strip)
				.filter(line -> !line.isBlank() && !line.startsWith("#"))
				.filter(category -> !FALLBACK_CATEGORY.equals(category))
				.distinct()
				.sorted(Comparator.comparingInt(String::length).reversed())
				.toList();
		} catch (IOException e) {
			throw new IllegalStateException("빵 기록 분류표를 읽을 수 없습니다.", e);
		}
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		return value.replaceAll("\\s+", "");
	}
}
