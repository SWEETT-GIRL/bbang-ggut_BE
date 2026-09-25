package sweetgirl.croissant.recommendation.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import sweetgirl.croissant.recommendation.enums.EvidenceType;
import sweetgirl.croissant.recommendation.enums.ExpressionType;
import sweetgirl.croissant.recommendation.enums.KindSource;

/** 근거별 완성 문장 (설계 §5.3). 근거가 입증하는 것만 말하는지, 길이를 넘지 않는지. */
class ReasonTemplatesTest {

	private static final long STORE_ID = 42L;

	private static ReasonEvidence kind(EvidenceType type, String name, KindSource source) {
		return ReasonEvidence.ofKind(type, name, source);
	}

	private static List<String> textsOf(ReasonEvidence evidence) {
		return ReasonTemplates.candidates(evidence, STORE_ID).stream()
			.map(GeneratedReasonSentence::text).toList();
	}

	@Test
	@DisplayName("기록 종류는 조건형 하나와 특징형 둘 — '자주 드시는' 은 쓰지 않는다(빈도 미입증)")
	void recordedKind() {
		List<String> texts = textsOf(kind(EvidenceType.RECORDED_KIND_MATCH, "소금빵", KindSource.RECORDED));

		assertThat(texts).containsExactly(
			"기록에 남긴 소금빵 여기서도 살펴보세요", "대표 빵은 소금빵이에요", "소금빵이 대표 빵인 곳이에요");
		assertThat(texts).noneMatch(text -> text.contains("자주"));
	}

	@Test
	@DisplayName("추정 종류는 조건형 둘 — 단정하지 않는다")
	void inferredKind() {
		List<String> texts = textsOf(kind(EvidenceType.INFERRED_KIND_MATCH, "케이크", KindSource.INFERRED));

		assertThat(texts).containsExactly(
			"케이크 좋아하시면 살펴보세요", "케이크 찾는다면 눈여겨보세요", "대표 빵은 케이크예요", "케이크가 대표 빵인 곳이에요");
	}

	@Test
	@DisplayName("어미와 조사가 받침을 따른다 — '소금빵예요' · '케이크이' 가 나가지 않는다")
	void particlesFollowJongseong() {
		assertThat(textsOf(kind(EvidenceType.STORE_KIND, "소금빵", KindSource.STORE)))
			.containsExactly("대표 빵은 소금빵이에요", "소금빵이 대표 빵인 곳이에요");
		assertThat(textsOf(kind(EvidenceType.STORE_KIND, "케이크", KindSource.STORE)))
			.containsExactly("대표 빵은 케이크예요", "케이크가 대표 빵인 곳이에요");
	}

	@Test
	@DisplayName("표현을 바꿔도 근거 유형과 출처는 그대로다 — DESCRIBE 를 골랐다고 STORE_KIND 로 내려가지 않는다")
	void expressionDoesNotDowngradeEvidence() {
		ReasonEvidence evidence = kind(EvidenceType.INFERRED_KIND_MATCH, "스콘", KindSource.INFERRED);

		List<GeneratedReasonSentence> sentences = ReasonTemplates.candidates(evidence, STORE_ID);

		assertThat(sentences).allSatisfy(sentence -> {
			assertThat(sentence.evidence().type()).isEqualTo(EvidenceType.INFERRED_KIND_MATCH);
			assertThat(sentence.evidence().source()).isEqualTo(KindSource.INFERRED);
		});
		assertThat(sentences).extracting(GeneratedReasonSentence::expression)
			.containsExactly(ExpressionType.CONNECT, ExpressionType.CONNECT,
				ExpressionType.DESCRIBE, ExpressionType.DESCRIBE);
	}

	@Test
	@DisplayName("회원 태그와 가게 태그는 같은 문장을 쓴다 — 겹쳤다고 문장이 더 말할 수 있는 건 아니다")
	void memberAndStoreTagShareText() {
		String member = textsOf(ReasonEvidence.ofTag(EvidenceType.MEMBER_TAG_MATCH, "가성비")).get(0);
		String store = textsOf(ReasonEvidence.ofTag(EvidenceType.STORE_TAG, "가성비")).get(0);

		assertThat(member).isEqualTo(store).isEqualTo("가성비가 좋다는 평이 있는 곳이에요");
	}

	@ParameterizedTest
	@ValueSource(strings = {"가성비", "특별한메뉴", "디저트"})
	@DisplayName("허용 태그 셋은 '~다는 평이 있는' 으로 출처를 드러낸다 — 값이 방문자 투표 백분위라 단정하면 과하다")
	void tagTextsShowSource(String tagKey) {
		List<String> texts = textsOf(ReasonEvidence.ofTag(EvidenceType.STORE_TAG, tagKey));

		assertThat(texts).hasSize(1);
		assertThat(texts.get(0)).contains("평이 있는");
	}

	@Test
	@DisplayName("한글로 끝나지 않는 종류는 특징형을 못 만든다 — 조사를 추측해 붙이지 않는다")
	void nonHangulKindHasNoDescribe() {
		assertThat(textsOf(kind(EvidenceType.STORE_KIND, "MOF", KindSource.STORE))).isEmpty();
		assertThat(textsOf(kind(EvidenceType.INFERRED_KIND_MATCH, "MOF", KindSource.INFERRED)))
			.containsExactly("MOF 좋아하시면 살펴보세요", "MOF 찾는다면 눈여겨보세요");
	}

	@Test
	@DisplayName("모든 산출 문장이 35자 이하 — 운영 최장 종류(5자)로도, 그보다 긴 이름으로도")
	void neverExceedsMaxLength() {
		List<String> kinds = List.of("소금빵", "에그타르트", "크로크무슈", "아이스크림", "샌드베이글", "아주아주긴종류이름열자");

		for (String name : kinds) {
			for (EvidenceType type : List.of(EvidenceType.RECORDED_KIND_MATCH, EvidenceType.INFERRED_KIND_MATCH,
				EvidenceType.STORE_KIND)) {
				assertThat(textsOf(kind(type, name, KindSource.STORE)))
					.allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(ReasonTemplates.MAX_LENGTH));
			}
		}
		for (String tagKey : List.of("가성비", "특별한메뉴", "디저트")) {
			assertThat(textsOf(ReasonEvidence.ofTag(EvidenceType.STORE_TAG, tagKey)))
				.allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(ReasonTemplates.MAX_LENGTH));
		}
	}

	@Test
	@DisplayName("길이를 넘는 문장은 빼고 준다 — 자르지 않는다")
	void dropsInsteadOfTruncating() {
		String tooLong = "이름이아주아주아주길어서문장이서른다섯자를넘게만드는종류이름";

		List<String> texts = textsOf(kind(EvidenceType.RECORDED_KIND_MATCH, tooLong, KindSource.RECORDED));

		assertThat(texts).allSatisfy(text -> assertThat(text.length()).isLessThanOrEqualTo(ReasonTemplates.MAX_LENGTH));
		assertThat(texts).noneMatch(text -> text.endsWith("…"));
	}

	@Test
	@DisplayName("근거가 없으면 확인되는 사실만 — '자주 담기는' 은 빈도를 세지 않아 쓸 수 없다")
	void noEvidence() {
		List<GeneratedReasonSentence> sentences = ReasonTemplates.candidates(ReasonEvidence.none(), STORE_ID);

		assertThat(sentences).hasSize(1);
		assertThat(sentences.get(0).text()).isEqualTo("이번 코스에 함께 담아본 빵집이에요");
		assertThat(sentences.get(0).evidence().type()).isEqualTo(EvidenceType.NONE);
	}

	@Test
	@DisplayName("문장이 되는 태그는 Resolver 가 근거로 내주는 태그와 같은 집합이어야 한다")
	void tagSetsAgree() {
		for (String tagKey : ReasonEvidenceResolver.SENTENCE_TAGS) {
			assertThat(textsOf(ReasonEvidence.ofTag(EvidenceType.STORE_TAG, tagKey)))
				.as("Resolver 가 %s 를 근거로 주는데 문장이 없다", tagKey)
				.hasSize(1);
		}
	}

	@Test
	@DisplayName("결과마다 storeId 가 실린다 — 목록 순서만으로 짝지으면 다른 가게의 이유가 붙는다")
	void carriesStoreId() {
		assertThat(ReasonTemplates.candidates(kind(EvidenceType.STORE_KIND, "소금빵", KindSource.STORE), 777L))
			.allSatisfy(sentence -> assertThat(sentence.storeId()).isEqualTo(777L));
	}
}
