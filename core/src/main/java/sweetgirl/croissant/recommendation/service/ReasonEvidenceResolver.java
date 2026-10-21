package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.EvidenceType;
import sweetgirl.croissant.recommendation.enums.KindSource;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/**
 * 이유 문장이 쓸 수 있는 근거를 <b>정렬된 목록</b>으로 만든다 (설계 §4).
 *
 * <p>문턱 판정은 {@link AnchorBadgeResolver} 와 같은 규칙이다 — 배지와 이유가 다른 기준으로 말하면 한 화면에서
 * 어긋난다. 다만 <b>최종 선택 정책까지 같지는 않다</b>(설계 §6.2): 배지는 하나를 골라 문구를 만들고 축 극 근거까지
 * 쓰지만, 여기는 후보를 전부 순서대로 주고 축 근거는 담지 않는다. 축 근거를 안 담는 이유는 배지 쪽에 이미
 * 기준 불일치 문제가 있어서다(#801) — 라벨로 순위를 정하고 축으로 설명한다. 그것이 정리되기 전에 문장까지 끌어오지 않는다.
 *
 * <p><b>{@code hasAxis()} 로 개인화 전체를 막지 않는다.</b> {@code TasteQuery.of()} 는 축이 없어도 종류와 태그를
 * 그대로 싣는다. 축 없음은 축 근거를 못 쓴다는 뜻이지 종류 일치가 무효라는 뜻이 아니다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReasonEvidenceResolver {

	/**
	 * 문장을 검수한 태그만 (설계 §5.4). 이 셋은 dataops {@code store_tags.py} 의 {@code VOTE_TAGS} 에서
	 * <b>투표 항목 하나</b>에 대응한다 — 가성비={@code price_cheap} · 특별한메뉴={@code special_menu} ·
	 * 디저트={@code dessert_good}.
	 *
	 * <p>나머지는 합성이라 뺐다. 머물기는 커피 · 음료 · 차 · 좌석 · 오래 머물기 · 집중 · 대화 일곱을 합친 값이라
	 * <b>커피 평가만 높아도 올라간다</b> — 그 값으로 "머물기 좋은 곳" 이라고 쓰면 원천보다 구체적인 주장이 된다.
	 * 공간사진(넷) · 건강한맛(둘)도 같다. 웨이팅 · 대형은 투표가 아니라 규칙으로 낸 0~1 이라 가게 문턱 0.6 의 뜻이 다르다.
	 *
	 * <p><b>이것은 문장 정책이지 판정 규칙이 아니다</b> — 배지는 여덟 태그를 그대로 쓴다.
	 */
	static final Set<String> SENTENCE_TAGS = Set.of(
		TasteTag.VALUE.getKey(), TasteTag.SPECIAL_MENU.getKey(), TasteTag.DESSERT.getKey());

	/** 태그 동률은 {@link TasteTag} 선언 순으로 깬다 — 실험이 재현되게. 배지의 기존 동률 동작은 건드리지 않는다. */
	private static final Comparator<TasteTag> DECLARED_ORDER = Comparator.comparingInt(Enum::ordinal);

	/**
	 * 유효한 근거를 우선순위 순으로. 하나도 없으면 {@link ReasonEvidence#none()} 하나.
	 *
	 * <p>유형 간 순서는 {@link EvidenceType} 선언 순이고, 유형 안에서는 재료의 원래 순서를 따른다 —
	 * 종류는 프로필·프로필의 정렬(감쇠 점수 · lift)이 이미 뜻을 담고 있고, 태그는 곱(회원×가게) 또는 가게값이다.
	 */
	public static List<ReasonEvidence> resolve(TasteQuery taste, CandidateStoreRow row,
			RecommendationProperties.Score score) {
		List<String> signatureKinds = row.signatureKinds();
		List<ReasonEvidence> evidences = new ArrayList<>();

		addKindMatches(evidences, taste.topKinds(), signatureKinds,
			EvidenceType.RECORDED_KIND_MATCH, KindSource.RECORDED);
		addKindMatches(evidences, taste.inferredKinds(), signatureKinds,
			EvidenceType.INFERRED_KIND_MATCH, KindSource.INFERRED);
		addMemberTagMatches(evidences, taste, row, score);
		addStoreTags(evidences, row, score);
		addStoreKinds(evidences, signatureKinds);

		return evidences.isEmpty() ? List.of(ReasonEvidence.none()) : List.copyOf(evidences);
	}

	/** 회원 종류 목록의 순서를 그대로 둔다 — 앞에 있을수록 그 회원에게 강한 종류다. */
	private static void addKindMatches(List<ReasonEvidence> out, List<String> memberKinds,
			List<String> signatureKinds, EvidenceType type, KindSource source) {
		for (String kind : memberKinds) {
			if (signatureKinds.contains(kind)) {
				out.add(ReasonEvidence.ofKind(type, kind, source));
			}
		}
	}

	/**
	 * 회원·가게 양쪽이 도드라진 태그 — 곱이 큰 순. 강한 신호 가게가 모자라면 {@code reasonTags} 가 빈 map 을 주므로
	 * 이 유형 자체가 안 선다(한두 곳의 태그를 그 회원의 취향이라고 옮겨 적는 일을 막는다).
	 */
	private static void addMemberTagMatches(List<ReasonEvidence> out, TasteQuery taste, CandidateStoreRow row,
			RecommendationProperties.Score score) {
		Map<String, Double> memberTags = taste.reasonTags(score.getMinStrongStoresForTagReason());
		List<TasteTag> matched = new ArrayList<>();
		Map<TasteTag, Double> products = new EnumMap<>(TasteTag.class);
		for (Map.Entry<String, Double> memberTag : memberTags.entrySet()) {
			Double storeValue = row.tags().get(memberTag.getKey());
			if (!sentenceTag(memberTag.getKey()) || storeValue == null
				|| memberTag.getValue() <= score.memberNotableTagAbove(memberTag.getKey())
				|| storeValue <= score.getStoreNotableTagAbove()) {
				continue;
			}
			TasteTag.fromKey(memberTag.getKey()).ifPresent(tag -> {
				matched.add(tag);
				products.put(tag, memberTag.getValue() * storeValue);
			});
		}
		matched.sort(Comparator.<TasteTag>comparingDouble(products::get).reversed().thenComparing(DECLARED_ORDER));
		for (TasteTag tag : matched) {
			out.add(ReasonEvidence.ofTag(EvidenceType.MEMBER_TAG_MATCH, tag.getKey()));
		}
	}

	/** 가게만 도드라진 태그 — 가게값 큰 순. 회원 조건이 없다(설계 §4.4). */
	private static void addStoreTags(List<ReasonEvidence> out, CandidateStoreRow row,
			RecommendationProperties.Score score) {
		List<TasteTag> notable = new ArrayList<>();
		Map<TasteTag, Double> values = new EnumMap<>(TasteTag.class);
		for (Map.Entry<String, Double> storeTag : row.tags().entrySet()) {
			if (!sentenceTag(storeTag.getKey()) || storeTag.getValue() == null
				|| storeTag.getValue() <= score.getStoreNotableTagAbove()) {
				continue;
			}
			TasteTag.fromKey(storeTag.getKey()).ifPresent(tag -> {
				notable.add(tag);
				values.put(tag, storeTag.getValue());
			});
		}
		notable.sort(Comparator.<TasteTag>comparingDouble(values::get).reversed().thenComparing(DECLARED_ORDER));
		for (TasteTag tag : notable) {
			out.add(ReasonEvidence.ofTag(EvidenceType.STORE_TAG, tag.getKey()));
		}
	}

	/** 가게 대표 종류 — 정제된 목록의 원래 순서. 회원과의 연결은 없다. */
	private static void addStoreKinds(List<ReasonEvidence> out, List<String> signatureKinds) {
		for (String kind : signatureKinds) {
			out.add(ReasonEvidence.ofKind(EvidenceType.STORE_KIND, kind, KindSource.STORE));
		}
	}

	private static boolean sentenceTag(String tagKey) {
		return SENTENCE_TAGS.contains(tagKey);
	}
}
