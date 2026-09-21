package sweetgirl.croissant.personalization.dto.response;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.personalization.enums.QuizChoice;

/**
 * 검사가 드러낸 취향 라벨 — 맛 4 · 식감 3 · 속 · 스타일 = 9개 값(0~1).
 *
 * <p>기존 4축({@code axis})이 −1~+1 숫자 넷이라 짭짤·풍미·쫄깃을 담지 못해 따로 둔다. 키 이름은 dataops 사전의
 * 열 이름과 같은 말이다 — 가게 라벨이 적재되면 회원과 가게를 같은 이름으로 맞대게 된다(풍미는 {@code rich} 가
 * 아니라 {@code aroma} 다. 리뷰 태그의 {@code rich} 가 초코·치즈의 '진한 맛' 을 가리켜 혼동을 봤다).
 *
 * <p><b>검사를 푼 회원만 이 값을 갖는다.</b> 행동 신호에는 아직 라벨이 없다 — 가게 쪽 라벨이 와야 신호에서
 * 라벨을 만들 수 있다. 검사 전 회원에게는 {@code null} 로 나간다.
 *
 * <p>속·스타일은 갈래가 둘뿐이라 키 하나가 "꽉 찬 정도" · "트렌디한 정도" 를 뜻한다 — 심플을 고르면
 * {@code filling = 0}, 클래식을 고르면 {@code vibe = 0} 이다. 맛·식감은 갈래마다 키가 있어 고른 것만 1 이다.
 */
public record TasteLabels(
		@Schema(description = "달콤", example = "1") double sweet,
		@Schema(description = "담백", example = "0") double plain,
		@Schema(description = "짭짤", example = "0") double salty,
		@Schema(description = "풍미", example = "0") double aroma,
		@Schema(description = "부드러움", example = "0") double soft,
		@Schema(description = "바삭", example = "1") double crisp,
		@Schema(description = "쫄깃", example = "0") double chewy,
		@Schema(description = "속이 꽉 찬 정도 — 심플을 고르면 0", example = "1") double filling,
		@Schema(description = "트렌디한 정도 — 클래식을 고르면 0", example = "0") double vibe) {

	/**
	 * 고른 선택지에서 라벨을 만든다. 8문항 검사에서 한 축의 두 답이 갈리면 둘 다 1 이다 — 달콤과 짭짤을 하나씩
	 * 골랐으면 두 라벨이 모두 선다. 축 요약값처럼 평균 내 0 으로 만들지 않는다. "둘 다 좋아한다" 를
	 * "어느 쪽도 아니다" 로 바꾸면 없는 취향을 지어내는 것이다.
	 */
	public static TasteLabels of(List<QuizChoice> choices) {
		double sweet = 0;
		double plain = 0;
		double salty = 0;
		double aroma = 0;
		double soft = 0;
		double crisp = 0;
		double chewy = 0;
		double filling = 0;
		double vibe = 0;
		for (QuizChoice choice : choices) {
			switch (choice) {
				case SWEET -> sweet = choice.labelValue();
				case PLAIN -> plain = choice.labelValue();
				case SALTY -> salty = choice.labelValue();
				case AROMA -> aroma = choice.labelValue();
				case SOFT -> soft = choice.labelValue();
				case CRISP -> crisp = choice.labelValue();
				case CHEWY -> chewy = choice.labelValue();
				// 속·스타일은 두 갈래가 키 하나를 나눠 쓴다 — 반대쪽을 고르면 0 이라 값을 덮어쓰면 안 된다
				case FULL, SIMPLE -> filling = Math.max(filling, choice.labelValue());
				case CLASSIC, TRENDY -> vibe = Math.max(vibe, choice.labelValue());
			}
		}
		return new TasteLabels(sweet, plain, salty, aroma, soft, crisp, chewy, filling, vibe);
	}
}
