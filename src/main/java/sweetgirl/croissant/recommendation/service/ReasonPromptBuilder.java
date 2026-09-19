package sweetgirl.croissant.recommendation.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.personalization.enums.BbtiType;
import sweetgirl.croissant.personalization.enums.GenericKind;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 이유 문장 프롬프트를 조립한다 (#662 실험).
 *
 * <p><b>회원 식별자는 넣지 않는다.</b> 들어가는 것은 축 4개 · 태그 이름 · 빵 종류 · 유형 이름과 가게 공개 정보뿐이다.
 * Gemini 무료 등급은 입력이 제품 개선에 쓰일 수 있고 사람이 검토할 수 있어, 회원을 되짚을 수 있는 값(memberId ·
 * 닉네임)은 프롬프트에 올리지 않는다.
 *
 * <p><b>축 값과 축 이름은 프롬프트에 넣지 않는다.</b> 축은 우리가 가게를 고르려고 만든 내부 눈금이지 회원이
 * 아는 말이 아니다. 넣으면 모델이 그 형용사를 그대로 문장으로 돌려준다("바삭하고 담백한 빵집이에요") — 실측에서
 * 그랬다. 게다가 가게 축은 그 가게가 파는 <b>종류의 평균</b>이라, 형용사를 가게의 성질로 단정하면 사실보다 넓은
 * 말이 된다. 축은 후보를 고르고 순서를 정하는 데까지만 쓰고, 문장에는 회원이 알아보는 것만 넣는다 — 실제 빵
 * 종류와 태그다.
 *
 * <p>순수 조립이라 단위 테스트의 중심이다 — 네트워크 없이 변형별 결과 문자열을 그대로 확인할 수 있다.
 */
@Component
public class ReasonPromptBuilder {

	private static final String NO_VALUE = "없음";
	private static final int MAX_KINDS = 5;

	/**
	 * 모델이 쓸 수 있는 태그 목록. 프롬프트 파일에 글자로 박아 두면 사전이 바뀔 때마다 파일 셋을 손으로 고쳐야 하고,
	 * 빠뜨리면 없어진 태그를 쓰라고 하거나 새 태그를 쓰지 말라고 하게 된다(#758 에서 실제로 그랬다 — 노포 · 신상 · 아침 ·
	 * 저녁이 dataops #11 에서 빠졌는데 프롬프트에는 남아 있었다). 태그의 원본은 {@link TasteTag} 하나다.
	 */
	private static final String TAG_VOCABULARY = Arrays.stream(TasteTag.values())
		.map(TasteTag::getKey)
		.collect(Collectors.joining("·"));

	private final Map<ReasonPromptVariant, String> templates = new EnumMap<>(ReasonPromptVariant.class);
	private final RecommendationProperties recommendationProperties;

	public ReasonPromptBuilder(RecommendationProperties recommendationProperties) {
		this.recommendationProperties = recommendationProperties;
		for (ReasonPromptVariant variant : ReasonPromptVariant.values()) {
			templates.put(variant, load(variant.getPromptFile()));
		}
	}

	/**
	 * @param taste 회원 취향. 축이 없으면 축 줄이 "없음" 이 되고, 프롬프트는 가게 사실만으로 쓰게 된다
	 * @param typeCode 취향 유형 코드(BBTI). 없으면 유형 줄을 뺀다
	 * @param stores 룰이 이미 고르고 순서까지 정한 가게들 — 이 순서 그대로 프롬프트에 넣는다
	 * @param signatureMenus 가게별 시그니처 메뉴. 변형 B 만 쓴다. 없는 가게는 그 줄이 빠진다
	 */
	public String build(ReasonPromptVariant variant, TasteQuery taste, String typeCode,
			List<CandidateStoreRow> stores, Map<Long, List<String>> signatureMenus) {
		return templates.get(variant)
			.replace("{taste}", tasteBlock(taste, typeCode))
			.replace("{stores}", storesBlock(variant, taste, stores, signatureMenus))
			.replace("{storeCount}", String.valueOf(stores.size()))
			.replace("{tasteThreshold}", decimal(recommendationProperties.getScore().getTasteCosineThreshold()))
			.replace("{tagVocabulary}", TAG_VOCABULARY);
	}

	private String tasteBlock(TasteQuery taste, String typeCode) {
		List<String> lines = new ArrayList<>();
		if (typeCode != null) {
			lines.add("- 유형: " + BbtiType.labelOrCode(typeCode));
		}
		lines.add("- 좋아하는 태그: " + joinOrNone(sortedByWeight(taste.tags())));
		lines.add("- 자주 먹는 빵 종류: " + joinOrNone(taste.topKinds()));
		return String.join("\n", lines);
	}

	private String storesBlock(ReasonPromptVariant variant, TasteQuery taste, List<CandidateStoreRow> stores,
			Map<Long, List<String>> signatureMenus) {
		List<String> blocks = new ArrayList<>(stores.size());
		for (int index = 0; index < stores.size(); index++) {
			CandidateStoreRow row = stores.get(index);
			StringBuilder block = new StringBuilder();
			block.append(index + 1).append(". ").append(row.name())
				.append(" (storeId=").append(row.storeId()).append(", ")
				.append(row.region3rd() == null ? "동네 미상" : row.region3rd()).append(")\n");
			if (variant.isIncludesSignatureMenus()) {
				List<String> menus = signatureMenus.get(row.storeId());
				if (menus != null && !menus.isEmpty()) {
					block.append("   시그니처 메뉴: ").append(String.join(", ", limit(menus))).append("\n");
				}
			}
			block.append("   태그: ").append(joinOrNone(sortedByWeight(row.tags()))).append("\n");
			block.append("   빵 종류: ").append(joinOrNone(limit(specificKinds(row.kinds()))));
			if (variant.isIncludesRuleEvidence()) {
				block.append("\n   선정 근거: ").append(evidenceText(taste, row));
			}
			blocks.add(block.toString());
		}
		return String.join("\n", blocks);
	}

	private String evidenceText(TasteQuery taste, CandidateStoreRow row) {
		return "취향 일치도 " + decimal(row.tasteFit())
			+ " · 겹치는 태그 " + joinOrNone(intersection(taste.tags(), row.tags()))
			+ " · 겹치는 빵 종류 " + joinOrNone(intersection(taste.topKinds(), row.kinds()))
			+ " · 인기도 " + decimal(popularityPrior(row.popularityScore()));
	}

	/** 후보 SQL 의 prior 와 같은 정규화 — 상한 대비 비율이라 프롬프트에서도 0~1 로 읽힌다 */
	private double popularityPrior(double popularityScore) {
		double cap = recommendationProperties.getScore().getPrior().getPopularityCap();
		return cap <= 0 ? 0 : Math.min(1, popularityScore / cap);
	}

	private static String decimal(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

	/** 가중치가 큰 태그가 앞에 오게 — 모델이 앞쪽을 더 본다 */
	private static List<String> sortedByWeight(Map<String, Double> weights) {
		if (weights == null || weights.isEmpty()) {
			return List.of();
		}
		return weights.entrySet().stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() > 0)
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.map(Map.Entry::getKey)
			.toList();
	}

	private static List<String> intersection(Map<String, Double> weights, Map<String, Double> other) {
		if (other == null || other.isEmpty()) {
			return List.of();
		}
		Set<String> otherKeys = other.entrySet().stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() > 0)
			.map(Map.Entry::getKey)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		return sortedByWeight(weights).stream().filter(otherKeys::contains).toList();
	}

	private static List<String> intersection(List<String> kinds, List<String> other) {
		if (kinds == null || other == null) {
			return List.of();
		}
		Set<String> otherKinds = new LinkedHashSet<>(other);
		return kinds.stream().filter(otherKinds::contains).toList();
	}

	/** 가게 종류에서 일반 종류(빵 · 기타 · 요거트)를 뺀다 — 넣으면 "기타·빵을 파는 곳" 같은 문장이 나온다 */
	private static List<String> specificKinds(List<String> kinds) {
		if (kinds == null) {
			return List.of();
		}
		return kinds.stream().filter(kind -> !GenericKind.isGeneric(kind)).toList();
	}

	private static List<String> limit(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.size() <= MAX_KINDS ? values : values.subList(0, MAX_KINDS);
	}

	private static String joinOrNone(List<String> values) {
		return values == null || values.isEmpty() ? NO_VALUE : String.join(", ", values);
	}

	private static String load(String promptFile) {
		try {
			return new ClassPathResource(promptFile).getContentAsString(StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("이유 문장 프롬프트를 읽을 수 없습니다: " + promptFile, e);
		}
	}
}
