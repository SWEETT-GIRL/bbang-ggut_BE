package sweetgirl.croissant.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.Particles;
import sweetgirl.croissant.personalization.enums.TasteTag;
import sweetgirl.croissant.recommendation.enums.EvidenceType;
import sweetgirl.croissant.recommendation.enums.ExpressionType;

/**
 * 근거 하나로 만들 수 있는 문장들 (설계 §5.3).
 *
 * <p>틀에 말을 자유롭게 끼우지 않는다 — 조합만큼 검수해야 하기 때문이다. <b>근거별로 완성된 문장</b>을 손으로 써서
 * 검수했고, 구멍은 종류 이름 한 자리뿐이다.
 *
 * <p><b>근거가 입증하는 것만 말한다.</b> 대표 종류 데이터는 "이 가게의 대표 빵이 무엇인가" 까지 입증한다 — 평판도
 * ({@code "소금빵으로 알려진 곳"}), 지금 판다는 사실도({@code "소금빵도 있는 곳"}) 입증하지 않는다. 기록 종류도
 * 빈도를 입증하지 않아({@code top_kinds} 는 기록 한 건으로도 들어간다) {@code "자주 드시는"} 은 못 쓴다.
 *
 * <p>모든 근거가 두 표현을 갖지는 않는다. 태그 근거는 {@link ExpressionType#DESCRIBE} 하나로 충분하고,
 * {@link EvidenceType#MEMBER_TAG_MATCH} 와 {@link EvidenceType#STORE_TAG} 는 <b>같은 문장</b>을 쓴다 —
 * 회원 태그가 겹쳤다고 문장이 더 말할 수 있는 것은 아니다. 개인화 근거를 썼는지는 {@code evidence.type()} 이 구분한다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ReasonTemplates {

	/** 두 줄에 온전히 들어가는 길이 (FE 실측). 넘으면 앱이 말줄임표로 끊으므로 짧은 템플릿으로 내려간다 */
	static final int MAX_LENGTH = 35;

	/** 근거가 하나도 없을 때 — 확인되는 사실만 말한다. "자주 담기는" 은 저장·담기 빈도를 세지 않아 쓸 수 없다 */
	static final String NO_EVIDENCE_TEXT = "이번 코스에 함께 담아본 빵집이에요";
	static final String NO_EVIDENCE_TEMPLATE_ID = "NONE";

	/**
	 * 태그별 완성 문장 — <b>평가의 출처가 드러나는 특징형</b>이다. 값이 방문자 투표의 백분위라 "가성비가 좋은 곳" 이라고
	 * 단정하면 원천보다 센 주장이 된다. "~다는 평이 있는" 이 데이터가 말하는 그대로다.
	 *
	 * <p>여기 있는 셋만 문장이 된다({@link ReasonEvidenceResolver#SENTENCE_TAGS} 와 같은 집합이어야 한다).
	 */
	private static final Map<String, String> TAG_TEXTS = Map.of(
		TasteTag.VALUE.getKey(), "가성비가 좋다는 평이 있는 곳이에요",
		TasteTag.SPECIAL_MENU.getKey(), "특별한 메뉴가 있다는 평이 있는 곳이에요",
		TasteTag.DESSERT.getKey(), "디저트가 맛있다는 평이 있는 곳이에요");

	/**
	 * 그 근거로 만들 수 있는 문장 전부. 길이를 넘는 것은 빼고 준다 — <b>자르지 않는다.</b>
	 * 만들 수 있는 문장이 없으면 빈 목록이다(부르는 쪽이 다음 근거로 간다).
	 *
	 * <p>{@link EvidenceType#NONE} 만 예외로 늘 하나를 준다 — 그것이 마지막 자리다.
	 */
	public static List<GeneratedReasonSentence> candidates(ReasonEvidence evidence, long storeId) {
		List<GeneratedReasonSentence> out = new ArrayList<>();
		String subject = evidence.subject();
		switch (evidence.type()) {
			case RECORDED_KIND_MATCH -> {
				add(out, storeId, evidence, ExpressionType.CONNECT, "RECORDED_KIND_CONNECT",
					"기록에 남긴 " + subject + " 여기서도 살펴보세요");
				addKindDescribe(out, storeId, evidence, subject);
			}
			case INFERRED_KIND_MATCH -> {
				add(out, storeId, evidence, ExpressionType.CONNECT, "INFERRED_KIND_CONNECT",
					subject + " 좋아하시면 살펴보세요");
				add(out, storeId, evidence, ExpressionType.CONNECT, "INFERRED_KIND_CONNECT_2",
					subject + " 찾는다면 눈여겨보세요");
				addKindDescribe(out, storeId, evidence, subject);
			}
			case MEMBER_TAG_MATCH, STORE_TAG -> add(out, storeId, evidence, ExpressionType.DESCRIBE,
				"TAG_DESCRIBE_" + subject, TAG_TEXTS.get(subject));
			case STORE_KIND -> addKindDescribe(out, storeId, evidence, subject);
			case NONE -> out.add(new GeneratedReasonSentence(storeId, NO_EVIDENCE_TEXT, NO_EVIDENCE_TEMPLATE_ID,
				evidence, ExpressionType.DESCRIBE));
			default -> throw new IllegalStateException("모르는 근거 유형: " + evidence.type());
		}
		return List.copyOf(out);
	}

	/** 근거를 못 살릴 때 마지막으로 서는 문장 — 코스 단위 생성기가 이 자리를 쓴다 */
	public static GeneratedReasonSentence fallback(long storeId) {
		return new GeneratedReasonSentence(storeId, NO_EVIDENCE_TEXT, NO_EVIDENCE_TEMPLATE_ID,
			ReasonEvidence.none(), ExpressionType.DESCRIBE);
	}

	/**
	 * 종류를 가게 특징으로 말하는 두 문장. 어미 · 조사가 붙으므로 <b>한글로 끝나는 종류만</b> 쓴다 —
	 * 영문 · 숫자 이름에 조사를 추측해 붙이지 않는다(설계 §5.3).
	 */
	private static void addKindDescribe(List<GeneratedReasonSentence> out, long storeId, ReasonEvidence evidence,
			String kind) {
		if (!Particles.endsWithHangul(kind)) {
			return;
		}
		add(out, storeId, evidence, ExpressionType.DESCRIBE, "KIND_DESCRIBE",
			"대표 빵은 " + kind + Particles.copula(kind));
		add(out, storeId, evidence, ExpressionType.DESCRIBE, "KIND_DESCRIBE_2",
			kind + Particles.subject(kind) + " 대표 빵인 곳이에요");
	}

	/** 길이를 넘으면 담지 않는다 — 자르면 어미가 잘려 말이 끊긴다 */
	private static void add(List<GeneratedReasonSentence> out, long storeId, ReasonEvidence evidence,
			ExpressionType expression, String templateId, String text) {
		if (text != null && text.length() <= MAX_LENGTH) {
			out.add(new GeneratedReasonSentence(storeId, text, templateId, evidence, expression));
		}
	}
}
