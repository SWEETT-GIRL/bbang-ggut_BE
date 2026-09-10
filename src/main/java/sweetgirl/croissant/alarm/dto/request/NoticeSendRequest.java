package sweetgirl.croissant.alarm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.alarm.enums.NotificationChannel;

/**
 * 전체 공지 발송. 새 버전 소식처럼 "무엇이 생겼는지"를 알린다.
 *
 * <p>업데이트를 강제하거나 스토어로 보내는 건 app/version-policy가 이미 한다. 여기는 그것과
 * 겹치지 않게, 무엇이 바뀌었고 어디로 가면 보이는지를 말하는 자리다.
 */
@Getter
@NoArgsConstructor
public class NoticeSendRequest {

	@NotBlank
	@Size(max = 200)
	private String title;

	@NotBlank
	@Size(max = 2000)
	private String body;

	/** 탭했을 때 갈 앱 내부 경로(/course/12 같은). 없으면 알림함에 머문다. */
	@Size(max = 500)
	private String link;

	/**
	 * 어디까지 내보낼지. {@code IN_APP}이면 알림함에만 쌓고, {@code PUSH}면 FCM까지 보낸다.
	 *
	 * <p>생략하면 {@code PUSH}다 — 지금까지 공지는 늘 울렸고, 필드를 안 보내는 호출이 조용히
	 * 안 울리게 되는 편이 더 나쁘다. 울리지 않기를 원하면 그것을 말해야 한다.
	 */
	private NotificationChannel channel;

	/** 생략된 채널을 기본값으로 메운다. */
	public NotificationChannel channelOrDefault() {
		return channel == null ? NotificationChannel.PUSH : channel;
	}
}
