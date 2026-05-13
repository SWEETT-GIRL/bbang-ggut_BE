package sweetgirl.croissant.alarm.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Builder;
import lombok.Getter;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.store.entity.Store;

@Getter
@Builder
public class StoreNotificationResponse {

	private Long id;
	private Long storeId;
	private String storeName;
	private String message;
	@JsonProperty("isRead")
	private boolean isRead;
	private LocalDateTime sentAt;
	private LocalDateTime readAt;

	public static StoreNotificationResponse from(StoreNotification storeNotification) {
		Store store = storeNotification.getStore();
		return StoreNotificationResponse.builder()
			.id(storeNotification.getId())
			.storeId(store != null ? store.getId() : null)
			.storeName(store != null ? store.getName() : null)
			.message(storeNotification.getMessage())
			.isRead(storeNotification.getReadAt() != null)
			.sentAt(storeNotification.getSentAt())
			.readAt(storeNotification.getReadAt())
			.build();
	}

}
