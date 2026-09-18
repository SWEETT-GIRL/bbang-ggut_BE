package sweetgirl.croissant.personalization.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

/**
 * 빵BTI 4축. 가게(store_profile)와 사용자(user_taste_profile)가 같은 컬럼 이름으로 갖는다.
 *
 * 값의 약속은 A극 −1 / B극 +1 이고, 메뉴 종류의 극값을 가중 평균한 것이라 −1~+1 사이 소수다.
 *   ① 맛 taste: 달콤 / 담백   ② 식감 texture: 부드 / 바삭
 *   ③ 속 filling: 풍부 / 심플  ④ vibe: <b>고전 빵 / 신상 빵</b> (코드 글자 O / N)
 * ④ 는 <b>가게 분위기가 아니라 빵의 성질</b>이다 — 크로플·크룽지·앙버터·소금빵·퀸아망이 +1,
 * 단팥빵·소보로·꿀빵·고구마빵이 −1. 가게 인테리어·분위기는 tags 의 카페형·노포·신상·동네빵집·데이트가 맡는다.
 * 축에 또 넣으면 점수식(코사인 + 0.5×태그 내적)이 같은 신호를 두 번 센다.
 * 네 값이 전부 NULL 이면 Hibernate 가 임베디드 자체를 null 로 돌려주므로, 소비자는 null 을 "근거 없음"으로 본다.
 * 코사인과 중심화(평균 기준 부호)는 여기 두지 않는다 — #661 의 도메인 몫이다.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TasteAxis {

	/** 이 미만이면 그 축은 "중간"으로 <b>표시</b>한다. 타입 계산에는 관여하지 않는다. */
	public static final BigDecimal WEAK_THRESHOLD = new BigDecimal("0.15");

	/** 컬럼 NUMERIC(4,3) 의 소수 자릿수. 축 값을 계산해 넣는 쪽이 이 자리로 반올림한다. */
	public static final int SCALE = 3;

	/** 축 값의 범위 — DB 의 ck_*_axis_range 와 같다. 계산 결과는 이 안으로 자른다 */
	public static final double MIN_VALUE = -1d;
	public static final double MAX_VALUE = 1d;

	@Column(name = "axis_taste", precision = 4, scale = 3)
	private BigDecimal taste;

	@Column(name = "axis_texture", precision = 4, scale = 3)
	private BigDecimal texture;

	@Column(name = "axis_filling", precision = 4, scale = 3)
	private BigDecimal filling;

	@Column(name = "axis_vibe", precision = 4, scale = 3)
	private BigDecimal vibe;

	private TasteAxis(BigDecimal taste, BigDecimal texture, BigDecimal filling, BigDecimal vibe) {
		this.taste = taste;
		this.texture = texture;
		this.filling = filling;
		this.vibe = vibe;
	}

	public static TasteAxis of(BigDecimal taste, BigDecimal texture, BigDecimal filling, BigDecimal vibe) {
		return new TasteAxis(taste, texture, filling, vibe);
	}

	/** 네 축이 모두 있어야 벡터로 쓸 수 있다. 하나라도 비면 코사인이 정의되지 않는다. */
	public boolean isComplete() {
		return taste != null && texture != null && filling != null && vibe != null;
	}

	/**
	 * 16타입 코드 — 4글자. store_profile.bread_type 과 user_taste_profile.type_code 가 같은 규칙을 쓴다.
	 *
	 * <pre>
	 *   자리  축          −1 극          +1 극
	 *   1     맛          S 달콤         P 담백
	 *   2     식감        F 폭신         C 바삭
	 *   3     속          R 꽉 찬        L 심플
	 *   4     빵의 신상    O 고전         N 신상
	 * </pre>
	 * 예: 담백·바삭·심플·신상 = {@code PCLN}, 달콤·폭신·꽉 찬·고전 = {@code SFRO}.
	 * 여덟 글자가 전부 달라서 MBTI 처럼 코드만 보고 읽힌다.
	 *
	 * <p><b>부호만 본다.</b> 축 값이 0 보다 크면 +1 극 글자다. 0 에 가깝다고 −1 극으로 접지 않는다 —
	 * 접으면 애매한 대상이 전부 {@code SFRO} 로 몰린다. dataops 구현에 그 버그가 있었고 가게 6,272곳이
	 * 잘못된 타입을 받았다(2026-09-10). 0 은 −1 극이다. 축이 약한지는 {@link #isWeak}로 따로 물어본다.
	 *
	 * <p>코드는 식별자로만 쓴다. 자리별 조건이 필요하면 코드를 자르지 말고 축 값을 직접 본다.
	 *
	 * @throws PersonalizationException {@code TASTE_AXIS_INCOMPLETE} — 네 축이 다 있지 않을 때. 근거가 없으면 타입도 없다.
	 *         부르는 쪽이 {@link #isComplete()} 로 먼저 거르는 게 약속이라, 여기 오면 서버 버그다.
	 */
	public String typeCode() {
		if (!isComplete()) {
			throw new PersonalizationException(PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE);
		}
		return new StringBuilder(4)
			.append(pole(taste, 'S', 'P'))
			.append(pole(texture, 'F', 'C'))
			.append(pole(filling, 'R', 'L'))
			.append(pole(vibe, 'O', 'N'))
			.toString();
	}

	/** 표시용 "중간" 판정. 타입 계산에는 쓰지 않는다 — 여기 쓰면 위의 버그가 된다. */
	public boolean isWeak(BigDecimal axisValue) {
		return axisValue != null && axisValue.abs().compareTo(WEAK_THRESHOLD) < 0;
	}

	private static char pole(BigDecimal value, char negative, char positive) {
		return value.signum() > 0 ? positive : negative;
	}
}
