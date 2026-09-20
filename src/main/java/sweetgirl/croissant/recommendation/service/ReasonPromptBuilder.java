package sweetgirl.croissant.recommendation.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import sweetgirl.croissant.personalization.enums.GenericKind;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.ReasonPromptVariant;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 이유 문장 프롬프트를 조립한다 (#662 실험).
 *
 * <p><b>회원 식별자는 넣지 않는다.</b> 들어가는 것은 회원의 태그 이름 · 빵 종류와 가게 공개 정보(동네 · 태그 · 빵 종류 ·
 * 시그니처 메뉴)뿐이다.
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
	 * 빠뜨리면 없어진 태그를 쓰라고 하거나 새 태그를 쓰지 말라고 하게 된다(#758 에서 실제로 그랬다). 태그의 원본은
	 * {@link TasteTag} 하나다.
	 *
	 * <p>데이터 키가 아니라 표시 문구를 쓴다 — 배지 칩과 이유 문장이 같은 말을 하고, "공간사진" 같은 데이터 이름이
	 * 문장에 새지 않는다(#759 리뷰). 문구 "건강·신선" 에 가운뎃점이 있어 구분자는 쉼표다.
	 */
	private static final String TAG_VOCABULARY = Arrays.stream(TasteTag.values())
		.map(TasteTag::getLabel)
		.collect(Collectors.joining(", "));

	/**
	 * 세 변형이 함께 쓰는 규칙. 파일마다 복사해 두면 한 곳을 빠뜨렸을 때 "무엇이 효과였나" 를 가릴 수 없게 된다
	 * (#768 리뷰). 예시는 재료 사용법이라 변형마다 달라도 되므로 변형 파일에 남긴다.
	 */
	private static final String FORM_RULE = load("recommendation-reason-form-rule.txt").strip();

	/** 태그를 문장에 그대로 옮기지 못하게 하는 규칙 — 랩 실험에서 "특별한 메뉴" 가 문장의 절반을 덮었다 */
	private static final String TAG_RULE = load("recommendation-reason-tag-rule.txt").strip();

	private final Map<ReasonPromptVariant, String> templates = new EnumMap<>(ReasonPromptVariant.class);
	private final Map<ReasonPromptVariant, String> versions = new EnumMap<>(ReasonPromptVariant.class);
	private final RecommendationProperties recommendationProperties;

	public ReasonPromptBuilder(RecommendationProperties recommendationProperties) {
		this.recommendationProperties = recommendationProperties;
		for (ReasonPromptVariant variant : ReasonPromptVariant.values()) {
			String template = load(variant.getPromptFile());
			templates.put(variant, template);
			// 해시 입력에 템플릿 밖의 재료까지 넣는다 — 태그 사전이나 문턱이 바뀌면 모델에게 가는 글자가
			// 달라지는데 템플릿만 보면 판이 같아 보인다(#768 리뷰)
			versions.put(variant, "reason-" + variant.name() + "-" + digest(template + FORM_RULE + TAG_RULE
				+ TAG_VOCABULARY + thresholds()));
		}
	}

	/**
	 * 이 문장을 어느 프롬프트가 썼는지 — 변형 이름과 템플릿 내용 해시다(예: {@code reason-B-3f9c2e}).
	 *
	 * <p>변형 이름만 남기면 <b>문구를 깎는 순간 판이 섞인다.</b> 같은 "reason-B" 인데 어제 프롬프트와 오늘 프롬프트가
	 * 달라, 나중에 추천 행을 놓고 "어느 판이 쓴 문장인가" 를 가릴 수 없다. 내용에서 뽑으므로 사람이 버전을 올려
	 * 적는 것을 잊을 수 없고, 한 글자만 고쳐도 값이 달라진다.
	 *
	 * <p>{@code recommendation.prompt_version} 이 20자라 해시는 6자만 쓴다 — 우리가 가진 판이 열 몇 개일 자리에서
	 * 충돌은 사실상 없다.
	 */
	public String versionOf(ReasonPromptVariant variant) {
		return versions.get(variant);
	}

	/**
	 * 해시에 넣는 문턱 · 상한. 회원 문턱은 <b>키 순으로 정렬해</b> 적는다 — {@code HashMap} 의 출력 순서에 기대면
	 * 같은 설정인데 판 이름이 달라질 수 있고, 그러면 "같은 판이면 같은 해시" 가 깨진다.
	 */
	private String thresholds() {
		RecommendationProperties.Score score = recommendationProperties.getScore();
		return new TreeMap<>(score.getMemberNotableTagAbove()) + "|" + score.getStoreNotableTagAbove()
			+ "|" + MAX_KINDS;
	}

	private static String digest(String template) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(template.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash, 0, 3);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
		}
	}

	/**
	 * @param taste 회원 취향. 좋아하는 태그와 자주 먹는 종류만 쓴다(축은 쓰지 않는다). 비었거나 태그 근거가 모자라면(강한 신호
	 *              가게 수 미달) 그 줄이 "없음" 이 되고, 프롬프트는 가게 사실만으로 쓰게 된다
	 * @param stores 룰이 이미 고르고 순서까지 정한 가게들 — 이 순서 그대로 프롬프트에 넣는다
	 * @param signatureMenus 가게별 시그니처 메뉴. 변형 B 만 쓴다. 없는 가게는 그 줄이 빠진다
	 */
	public String build(ReasonPromptVariant variant, TasteQuery taste, List<CandidateStoreRow> stores,
			Map<Long, List<String>> signatureMenus) {
		return templates.get(variant)
			.replace("{taste}", tasteBlock(taste))
			.replace("{stores}", storesBlock(variant, taste, stores, signatureMenus))
			.replace("{storeCount}", String.valueOf(stores.size()))
			.replace("{tasteThreshold}", decimal(recommendationProperties.getScore().getTasteCosineThreshold()))
			.replace("{formRule}", FORM_RULE)
			.replace("{tagRule}", TAG_RULE)
			.replace("{tagVocabulary}", TAG_VOCABULARY);
	}

	/**
	 * 회원 쪽은 좋아하는 태그와 자주 먹는 종류뿐이다. 유형 이름은 넣지 않는다 — 네 축의 부호를 이름 하나로 요약한 것이라 축과
	 * 같은 문제(가게 쪽 근거 없이 유형과 가게를 잇는 문장)를 안고, 유형 이름 자체를 앱에서 없애기로 했다(09-19 사용자 결정).
	 */
	private String tasteBlock(TasteQuery taste) {
		List<String> lines = new ArrayList<>();
		lines.add("- 좋아하는 태그: " + joinOrNone(labels(notableMemberTags(reasonTags(taste)))));
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
			block.append("   태그: ").append(joinOrNone(labels(notableStoreTags(row.tags())))).append("\n");
			block.append("   빵 종류: ").append(joinOrNone(limit(specificKinds(row.kinds()))));
			if (variant.isIncludesRuleEvidence()) {
				block.append("\n   선정 근거: ").append(evidenceText(taste, row));
			}
			blocks.add(block.toString());
		}
		return String.join("\n", blocks);
	}

	/** 근거가 모자란 회원(강한 신호 가게 수 미달)의 태그는 없는 것으로 쓴다 — 배지와 같은 판정 */
	private Map<String, Double> reasonTags(TasteQuery taste) {
		return taste.reasonTags(recommendationProperties.getScore().getMinStrongStoresForTagReason());
	}

	private String evidenceText(TasteQuery taste, CandidateStoreRow row) {
		return "취향 일치도 " + decimal(row.tasteFit())
			+ " · 겹치는 태그 " + joinOrNone(labels(sharedNotableTags(reasonTags(taste), row.tags())))
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

	/** 회원 쪽 도드라진 태그 — 태그별 문턱. 배지와 같다 */
	private List<TasteTag> notableMemberTags(Map<String, Double> weights) {
		RecommendationProperties.Score score = recommendationProperties.getScore();
		return notableTags(weights, score::memberNotableTagAbove);
	}

	/** 가게 쪽 도드라진 태그 — 문턱 하나. 배지와 같다 */
	private List<TasteTag> notableStoreTags(Map<String, Double> weights) {
		double above = recommendationProperties.getScore().getStoreNotableTagAbove();
		return notableTags(weights, key -> above);
	}

	/**
	 * 도드라진 태그만, 값이 큰 순으로(모델이 앞쪽을 더 본다). v5 는 모든 가게가 8키를 다 갖고 표가 없는 가게도 0.5 라, "0 보다 크면
	 * 있다" 로 거르면 가게마다 8개가 거의 다 나열된다(#759 리뷰). 사전 밖 키(적재가 사전보다 앞서거나 뒤처진 옛 키)는 뺀다 —
	 * 프롬프트가 "사전에 적힌 것만 쓴다" 고 하므로.
	 */
	private static List<TasteTag> notableTags(Map<String, Double> weights, ToDoubleFunction<String> aboveOf) {
		if (weights == null || weights.isEmpty()) {
			return List.of();
		}
		return weights.entrySet().stream()
			.filter(entry -> entry.getValue() != null && entry.getValue() > aboveOf.applyAsDouble(entry.getKey()))
			.sorted(Map.Entry.<String, Double>comparingByValue().reversed()
				.thenComparing(Map.Entry.comparingByKey()))
			.map(entry -> TasteTag.fromKey(entry.getKey()))
			.flatMap(Optional::stream)
			.toList();
	}

	/** 회원 · 가게 둘 다에서 도드라진 태그 — 배지의 태그 일치와 같은 조건이다 */
	private List<TasteTag> sharedNotableTags(Map<String, Double> member, Map<String, Double> store) {
		Set<TasteTag> storeTags = EnumSet.noneOf(TasteTag.class);
		storeTags.addAll(notableStoreTags(store));
		return notableMemberTags(member).stream().filter(storeTags::contains).toList();
	}

	private static List<String> labels(List<TasteTag> tags) {
		return tags.stream().map(TasteTag::getLabel).toList();
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

	/**
	 * 시그니처 메뉴 이름 손질 — dataops 가 쓰는 TEXT[] 라 NULL 원소 · 빈 문자열이 들어올 수 있다. {@code List.of} 는 null
	 * 원소에서 NPE 라 요청 전체가 500 으로 끝나고, 빈 문자열은 "시그니처 메뉴: , 잠봉뵈르" 같은 줄을 만든다 — 먼저 거른다.
	 *
	 * <p>프롬프트 재료를 만드는 쪽(실험 #662 · 서빙 #764)이 둘이라 여기 한 곳에 둔다.
	 */
	public static List<String> menuNamesOf(String[] signatureMenus) {
		if (signatureMenus == null) {
			return List.of();
		}
		return Arrays.stream(signatureMenus).filter(StringUtils::hasText).map(String::trim).distinct().toList();
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
