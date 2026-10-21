package sweetgirl.croissant.alarm.enums;

/**
 * 발송 결과. 채널마다 한 줄이고, 재시도는 새 줄을 만드는 대신 이 값을 고친다.
 *
 * <p>"보내지 않음"이 없는 이유: 수신 거부·토큰 없음처럼 애초에 보내지 않기로 한 경우는 대장에
 * 줄을 만들지 않는다. 대장은 시도한 것만 적는다 — 우편함에 행이 있는데 대장에 PUSH 줄이 없으면
 * 그것이 곧 "푸시는 보내지 않았다"는 뜻이다.
 */
public enum DeliveryStatus {
	SENT,
	FAILED
}
