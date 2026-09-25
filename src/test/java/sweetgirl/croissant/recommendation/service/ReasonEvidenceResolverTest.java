package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.recommendation.config.RecommendationProperties;
import sweetgirl.croissant.recommendation.enums.EvidenceType;
import sweetgirl.croissant.recommendation.enums.KindSource;
import sweetgirl.croissant.recommendation.repository.CandidateStoreRow;
import sweetgirl.croissant.recommendation.repository.TasteQuery;

/** 이유 문장의 근거 판정 (설계 §4). 문턱은 배지와 같고 최종 선택 정책만 다르다. */
class ReasonEvidenceResolverTest {

	private static final RecommendationProperties.Score RULES = new RecommendationProperties().getScore();

	/** 대표 종류와 태그만 쓰는 판정이라 축·거리는 고정값으로 둔다 */
	private static CandidateStoreRow store(Map<String, Double> tags, List<String> signatureKinds) {
		return new CandidateStoreRow(1L, "가게", "성수동", 37.5, 127.0, null, 100, 0, 1.0, 0.9, 5,
			0.9, 0.9, 0.1, 0.1, tags, signatureKinds, signatureKinds);
	}

	/** 축 있는 회원 */
	private static TasteQuery member(Map<String, Double> tags, List<String> topKinds, int strongStores,
			List<String> inferredKinds) {
		return new TasteQuery(0.8, 0.8, 0.5, 0.5, tags, topKinds, strongStores, inferredKinds);
	}

	private static List<EvidenceType> typesOf(List<ReasonEvidence> evidences) {
		return evidences.stream().map(ReasonEvidence::type).toList();
	}

	@Test
	@DisplayName("기록 종류가 추정 종류보다, 추정 종류가 태그보다 앞선다 — 선언 순서가 우선순위다")
	void ordersByEvidenceType() {
		TasteQuery taste = member(Map.of("가성비", 0.9), List.of("소금빵"), 4, List.of("스콘"));
		CandidateStoreRow row = store(Map.of("가성비", 0.9), List.of("소금빵", "스콘"));

		List<ReasonEvidence> evidences = ReasonEvidenceResolver.resolve(taste, row, RULES);

		assertThat(typesOf(evidences)).startsWith(
			EvidenceType.RECORDED_KIND_MATCH, EvidenceType.INFERRED_KIND_MATCH,
			EvidenceType.MEMBER_TAG_MATCH, EvidenceType.STORE_TAG);
	}

	@Test
	@DisplayName("같은 종류라도 출처를 구분한다 — 기록은 RECORDED, 추정은 INFERRED")
	void keepsKindSource() {
		TasteQuery recorded = member(Map.of(), List.of("소금빵"), 0, List.of());
		TasteQuery inferred = member(Map.of(), List.of(), 0, List.of("소금빵"));
		CandidateStoreRow row = store(Map.of(), List.of("소금빵"));

		assertThat(ReasonEvidenceResolver.resolve(recorded, row, RULES).get(0))
			.isEqualTo(new ReasonEvidence(EvidenceType.RECORDED_KIND_MATCH, "소금빵", KindSource.RECORDED));
		assertThat(ReasonEvidenceResolver.resolve(inferred, row, RULES).get(0))
			.isEqualTo(new ReasonEvidence(EvidenceType.INFERRED_KIND_MATCH, "소금빵", KindSource.INFERRED));
	}

	@Test
	@DisplayName("축이 없어도 종류 근거는 살아 있다 — 축 없음은 축 근거를 못 쓴다는 뜻이지 종류 일치가 무효라는 뜻이 아니다")
	void kindEvidenceSurvivesWithoutAxis() {
		TasteQuery noAxis = new TasteQuery(null, null, null, null, Map.of(), List.of("소금빵"), 0, List.of());

		List<ReasonEvidence> evidences = ReasonEvidenceResolver.resolve(noAxis, store(Map.of(), List.of("소금빵")), RULES);

		assertThat(evidences.get(0).type()).isEqualTo(EvidenceType.RECORDED_KIND_MATCH);
	}

	@Test
	@DisplayName("강한 신호 가게가 3곳이면 MEMBER_TAG_MATCH 가 없고, 4곳이면 생긴다 — 문턱은 '이상'")
	void memberTagNeedsStrongStores() {
		CandidateStoreRow row = store(Map.of("가성비", 0.9), List.of());

		assertThat(typesOf(ReasonEvidenceResolver.resolve(member(Map.of("가성비", 0.9), List.of(), 3, List.of()), row,
			RULES))).doesNotContain(EvidenceType.MEMBER_TAG_MATCH);
		assertThat(typesOf(ReasonEvidenceResolver.resolve(member(Map.of("가성비", 0.9), List.of(), 4, List.of()), row,
			RULES))).contains(EvidenceType.MEMBER_TAG_MATCH);
	}

	@Test
	@DisplayName("문턱과 정확히 같은 값은 근거가 아니다 — 가게 0.6 · 회원 0.5 는 '초과' 라야 한다")
	void thresholdsAreExclusive() {
		TasteQuery atMemberThreshold = member(Map.of("가성비", 0.5), List.of(), 4, List.of());

		assertThat(typesOf(ReasonEvidenceResolver.resolve(atMemberThreshold, store(Map.of("가성비", 0.9), List.of()),
			RULES))).doesNotContain(EvidenceType.MEMBER_TAG_MATCH);
		assertThat(typesOf(ReasonEvidenceResolver.resolve(member(Map.of("가성비", 0.9), List.of(), 4, List.of()),
			store(Map.of("가성비", 0.6), List.of()), RULES)))
			.doesNotContain(EvidenceType.MEMBER_TAG_MATCH, EvidenceType.STORE_TAG);
	}

	@Test
	@DisplayName("회원 태그 자격이 없어도 STORE_TAG 는 나온다 — 73% 가 가게 특징 설명을 못 받는 일을 막는다")
	void storeTagNeedsNoMemberCondition() {
		TasteQuery weakSignals = member(Map.of(), List.of(), 0, List.of());

		List<ReasonEvidence> evidences = ReasonEvidenceResolver.resolve(weakSignals,
			store(Map.of("디저트", 0.9), List.of()), RULES);

		assertThat(evidences.get(0)).isEqualTo(new ReasonEvidence(EvidenceType.STORE_TAG, "디저트", null));
	}

	@Test
	@DisplayName("문장을 검수하지 않은 태그는 근거가 아니다 — 머물기는 일곱 투표를 합친 값이라 커피 평가만 높아도 오른다")
	void onlySentenceTags() {
		TasteQuery stayLover = member(Map.of("머물기", 0.99, "공간사진", 0.99), List.of(), 4, List.of());
		CandidateStoreRow row = store(Map.of("머물기", 0.99, "공간사진", 0.99, "대형", 0.99), List.of());

		assertThat(ReasonEvidenceResolver.resolve(stayLover, row, RULES))
			.containsExactly(ReasonEvidence.none());
	}

	@Test
	@DisplayName("태그가 여럿이면 곱이 큰 순, 가게 태그는 가게값 큰 순")
	void ordersTagsByStrength() {
		TasteQuery taste = member(Map.of("가성비", 0.9, "디저트", 0.7), List.of(), 4, List.of());
		CandidateStoreRow row = store(Map.of("가성비", 0.7, "디저트", 0.95), List.of());

		List<ReasonEvidence> evidences = ReasonEvidenceResolver.resolve(taste, row, RULES);
		List<ReasonEvidence> memberTags = evidences.stream()
			.filter(evidence -> evidence.type() == EvidenceType.MEMBER_TAG_MATCH).toList();
		List<ReasonEvidence> storeTags = evidences.stream()
			.filter(evidence -> evidence.type() == EvidenceType.STORE_TAG).toList();

		// 회원×가게: 디저트 0.665 > 가성비 0.63
		assertThat(memberTags).extracting(ReasonEvidence::subject).containsExactly("디저트", "가성비");
		// 가게값: 디저트 0.95 > 가성비 0.7
		assertThat(storeTags).extracting(ReasonEvidence::subject).containsExactly("디저트", "가성비");
	}

	@Test
	@DisplayName("아무 재료도 없으면 NONE 하나만 준다 — 빈 목록을 주지 않는다")
	void noneWhenNothing() {
		TasteQuery empty = TasteQuery.empty();

		assertThat(ReasonEvidenceResolver.resolve(empty, store(Map.of(), List.of()), RULES))
			.containsExactly(ReasonEvidence.none());
	}

	@Test
	@DisplayName("회원 연결이 없어도 대표 종류가 있으면 STORE_KIND 가 남는다")
	void storeKindAsLastResort() {
		TasteQuery empty = TasteQuery.empty();

		List<ReasonEvidence> evidences = ReasonEvidenceResolver.resolve(empty,
			store(Map.of(), List.of("소금빵", "스콘")), RULES);

		assertThat(evidences).containsExactly(
			new ReasonEvidence(EvidenceType.STORE_KIND, "소금빵", KindSource.STORE),
			new ReasonEvidence(EvidenceType.STORE_KIND, "스콘", KindSource.STORE));
	}
}
