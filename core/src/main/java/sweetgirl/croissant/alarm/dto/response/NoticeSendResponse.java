package sweetgirl.croissant.alarm.dto.response;

import sweetgirl.croissant.alarm.enums.NotificationChannel;

/**
 * 공지를 몇 명에게 쌓았고 어디까지 내보냈는지.
 *
 * <p>채널을 함께 돌려주는 이유는 요청에서 생략할 수 있기 때문이다 — 보낸 쪽이 무엇으로 처리됐는지
 * 응답만 보고 알 수 있어야 한다.
 */
public record NoticeSendResponse(int recipientCount, NotificationChannel channel) {
}
