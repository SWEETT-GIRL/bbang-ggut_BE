package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

import lombok.Builder;
import lombok.Getter;
import sweetgirl.croissant.alarm.entity.StoreNotification;

@Getter
@Builder
public class StoreNotificationResponse {

	private Long id;
	private String storeName;
	private String message;
	private boolean isRead;
	private LocalDateTime sentAt;
	private LocalDateTime readAt;

	public static StoreNotificationResponse from(StoreNotification storeNotification) {
		return StoreNotificationResponse.builder()
			.id(storeNotification.getId())
			.storeName(storeNotification.getStore().getName())
			.message(storeNotification.getMessage())
			.isRead(false)
			.sentAt(LocalDateTime.now())
			.readAt(null)
			.build();
	}

}
