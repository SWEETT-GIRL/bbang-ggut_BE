package sweetgirl.croissant.personalization.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 취향 라벨 아홉 값 — 맛 4 · 식감 3 · 속 · 스타일. 회원({@code user_taste_profile})과 가게
 * ({@code store_recommendation_feature})가 같은 이름·같은 범위·같은 방향으로 갖는다.
 *
 * <p><b>{@link TasteAxis} 와 무엇이 다른가.</b> 축은 두 극 사이의 위치(−1~+1)라 갈래를 둘까지만 담는다.
 * 맛이 넷이 되면 짭짤과 담백이 똑같이 +1 이 되고 풍미는 어느 극도 아니라 0 이 된다. 라벨은 각 성질의
 * <b>세기</b>(0~1)라 그 넷이 따로 선다 — 소금빵처럼 짭짤과 풍미가 동시에 서는 것도 표현된다.
 *
 * <p><b>축과 부호가 반대인 자리가 있다.</b> {@code axis_filling} 은 꽉 참이 −1 · 심플이 +1 인데
 * {@code fillingRich} 는 "꽉 찬 정도" 라 꽉 참이 1 이다. {@code axis_vibe} 와 {@code vibeTrendy} 도 같다.
 * 축 값을 라벨에 그대로 옮기면 내적이 조용히 반대 가게를 올린다 — 예외도 안 나고 코스는 그럴듯하게
 * 나오므로 틀린 걸 알 방법이 없다. 이름이 방향을 말하게 둔 이유가 이것이다.
 *
 * <p>아홉 값은 <b>함께 채워지거나 함께 비어 있다</b>(DB CHECK 가 지킨다). 일부만 채우면 코사인이
 * 반쪽이 되는데, 그것도 예외가 아니라 "조금 덜 맞는 가게" 로 나온다. 네 값이 전부 NULL 이면 Hibernate 가
 * 임베디드 자체를 null 로 돌려주므로 소비자는 null 을 "근거 없음" 으로 본다.
 *
 * <p>코사인과 중심화는 여기 두지 않는다 — {@link TasteAxis} 와 같은 이유로 추천 도메인의 몫이다.
 * 중심화 상수는 가게 표의 평균이라 적재마다 바뀐다(2026-09-21 에 범위를 0~1 로 맞추면서
 * {@code axis_filling 0.035 → filling_rich 0.517} 로 부호까지 달라졌다). 상수를 코드에 박지 않는다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TasteLabelVector {

	/** 컬럼 NUMERIC(5,4) 의 소수 자릿수. 값을 계산해 넣는 쪽이 이 자리로 반올림한다. */
	public static final int SCALE = 4;

	/** 라벨 값의 범위 — DB 의 ck_*_label_range 와 같다. 가게 표도 같은 범위다. */
	public static final double MIN_VALUE = 0d;
	public static final double MAX_VALUE = 1d;

	/** 아홉 값을 배열로 다룰 때의 차원 수. 코사인을 도는 쪽이 길이를 이 값으로 확인한다. */
	public static final int DIMENSION = 9;

	@Column(name = "taste_sweet", precision = 5, scale = SCALE)
	private BigDecimal tasteSweet;

	@Column(name = "taste_plain", precision = 5, scale = SCALE)
	private BigDecimal tastePlain;

	@Column(name = "taste_salty", precision = 5, scale = SCALE)
	private BigDecimal tasteSalty;

	/** 풍미 — rich 가 아니라 aroma 다. 리뷰 태그의 rich 는 초코·치즈의 "진한 맛" 을 가리켜 다른 것이다. */
	@Column(name = "taste_aroma", precision = 5, scale = SCALE)
	private BigDecimal tasteAroma;

	@Column(name = "tex_soft", precision = 5, scale = SCALE)
	private BigDecimal texSoft;

	@Column(name = "tex_crisp", precision = 5, scale = SCALE)
	private BigDecimal texCrisp;

	@Column(name = "tex_chewy", precision = 5, scale = SCALE)
	private BigDecimal texChewy;

	/** 속이 꽉 찬 정도 — 심플할수록 0. {@code axis_filling}(꽉 참 −1)과 부호가 반대다. */
	@Column(name = "filling_rich", precision = 5, scale = SCALE)
	private BigDecimal fillingRich;

	/** 빵이 트렌디한 정도 — 클래식할수록 0. {@code axis_vibe}(클래식 −1)와 부호가 반대다. */
	@Column(name = "vibe_trendy", precision = 5, scale = SCALE)
	private BigDecimal vibeTrendy;

	private TasteLabelVector(BigDecimal tasteSweet, BigDecimal tastePlain, BigDecimal tasteSalty,
			BigDecimal tasteAroma, BigDecimal texSoft, BigDecimal texCrisp, BigDecimal texChewy,
			BigDecimal fillingRich, BigDecimal vibeTrendy) {
		this.tasteSweet = tasteSweet;
		this.tastePlain = tastePlain;
		this.tasteSalty = tasteSalty;
		this.tasteAroma = tasteAroma;
		this.texSoft = texSoft;
		this.texCrisp = texCrisp;
		this.texChewy = texChewy;
		this.fillingRich = fillingRich;
		this.vibeTrendy = vibeTrendy;
	}

	public static TasteLabelVector of(BigDecimal tasteSweet, BigDecimal tastePlain, BigDecimal tasteSalty,
			BigDecimal tasteAroma, BigDecimal texSoft, BigDecimal texCrisp, BigDecimal texChewy,
			BigDecimal fillingRich, BigDecimal vibeTrendy) {
		return new TasteLabelVector(tasteSweet, tastePlain, tasteSalty, tasteAroma,
			texSoft, texCrisp, texChewy, fillingRich, vibeTrendy);
	}

	/**
	 * 순서가 정해진 아홉 값 — 코사인을 도는 쪽이 이 순서를 가게 벡터와 맞춘다. 두 쪽이 같은 메서드를 쓰지
	 * 않으면 한쪽만 순서가 바뀌어도 조용히 다른 차원끼리 곱해진다.
	 */
	public double[] values() {
		return new double[] {
			doubleOf(tasteSweet), doubleOf(tastePlain), doubleOf(tasteSalty), doubleOf(tasteAroma),
			doubleOf(texSoft), doubleOf(texCrisp), doubleOf(texChewy),
			doubleOf(fillingRich), doubleOf(vibeTrendy)};
	}

	/** 아홉 값이 모두 있어야 벡터로 쓸 수 있다. 하나라도 비면 코사인이 반쪽이 된다. */
	public boolean isComplete() {
		return tasteSweet != null && tastePlain != null && tasteSalty != null && tasteAroma != null
			&& texSoft != null && texCrisp != null && texChewy != null
			&& fillingRich != null && vibeTrendy != null;
	}

	private static double doubleOf(BigDecimal value) {
		return value == null ? 0d : value.doubleValue();
	}
}
