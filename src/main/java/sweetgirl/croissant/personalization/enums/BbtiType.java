package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;

import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.exception.PersonalizationException;

/**
 * 빵BTI 48타입 — 4글자 코드. 자리마다 한 축이고, 자리별 글자 집합이 서로 겹치지 않아 글자만 보고 축을 안다.
 *
 * <pre>
 *   자리  축        글자
 *   1     맛        S 달콤 · P 담백 · T 짭짤 · M 풍미
 *   2     식감      F 부드 · C 바삭 · Y 쫄깃
 *   3     속        R 꽉 찬 · L 심플
 *   4     스타일    O 클래식 · N 트렌디
 * </pre>
 *
 * <p><b>16타입을 그대로 품는다.</b> 예전 16개(`[SP][FC][RL][ON]`)는 전부 이 48개 안에 있다 — 넓힌 것이지
 * 바꾼 것이 아니라, 저장된 옛 코드가 그대로 유효하고 {@code store_profile.bread_type} 의 16개와도 같은 체계다.
 * dataops 가 가게 쪽을 넓히기 전까지 회원에게만 T·M·Y 가 나오는 기간이 있지만, 코드로 회원과 가게를 맞추는
 * 자리는 없다(추천은 축 코사인으로 한다).
 *
 * <p><b>코드가 정해지는 길은 둘이다.</b> 검사를 푼 회원은 고른 갈래가 그대로 맛·식감 글자가 되고(48개 전부
 * 나올 수 있다), 검사 없이 행동 신호만 있는 회원은 축 부호로 정해져 옛 16개 안에 남는다({@link
 * sweetgirl.croissant.personalization.entity.TasteAxis#typeCode()}). 짭짤·풍미·쫄깃은 −1~+1 축 하나로
 * 표현되지 않으므로 신호만으로는 나올 수 없다.
 *
 * <p>화면 이름은 두지 않는다(#760). 카드 제목은 이름 없이 "{지역}에서 시작해볼까요?" 다. 코드는 식별자로만
 * 쓰고, 자리별 조건이 필요하면 코드를 자르지 말고 라벨이나 축 값을 직접 본다.
 */
public enum BbtiType {

	/** 달콤 */
	SFRO,
	SFRN,
	SFLO,
	SFLN,
	SCRO,
	SCRN,
	SCLO,
	SCLN,
	SYRO,
	SYRN,
	SYLO,
	SYLN,

	/** 담백 */
	PFRO,
	PFRN,
	PFLO,
	PFLN,
	PCRO,
	PCRN,
	PCLO,
	PCLN,
	PYRO,
	PYRN,
	PYLO,
	PYLN,

	/** 짭짤 */
	TFRO,
	TFRN,
	TFLO,
	TFLN,
	TCRO,
	TCRN,
	TCLO,
	TCLN,
	TYRO,
	TYRN,
	TYLO,
	TYLN,

	/** 풍미 */
	MFRO,
	MFRN,
	MFLO,
	MFLN,
	MCRO,
	MCRN,
	MCLO,
	MCLN,
	MYRO,
	MYRN,
	MYLO,
	MYLN;

	/** enum 이름이 곧 4글자 코드다. */
	public String code() {
		return name();
	}

	/**
	 * 저장된 코드에서 타입을 찾는다. 코드 체계는 DB CHECK 가 지키므로 못 찾으면 서버 약속이 깨진 것이다.
	 *
	 * @throws PersonalizationException {@code TASTE_AXIS_INCOMPLETE} — 규칙에 없는 코드
	 */
	public static BbtiType fromCode(String code) {
		return Arrays.stream(values())
			.filter(type -> type.name().equals(code))
			.findFirst()
			.orElseThrow(() -> new PersonalizationException(PersonalizationErrorCode.TASTE_AXIS_INCOMPLETE));
	}
}
