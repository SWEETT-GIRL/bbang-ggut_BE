package sweetgirl.croissant.alarm.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty; // boolean 필드 직렬화 시 isXxx → xxx 로 잘리는 문제 방지

import lombok.Builder; // 빌더 패턴으로 객체 생성
import lombok.Getter; // getter 자동 생성
import sweetgirl.croissant.member.entity.NotificationSetting; // NotificationSetting 엔티티에서 값을 읽어 변환

@Getter // 모든 필드에 getter 생성
@Builder // 빌더 패턴 사용
public class NotificationEnabledResponse {

	@JsonProperty("isNotificationEnabled") // JSON 직렬화 시 키 이름을 isNotificationEnabled 로 고정
	private boolean notificationEnabled; // 빵알람 수신 여부

	// NotificationSetting 엔티티를 응답 DTO 로 변환하는 정적 팩토리 메서드
	public static NotificationEnabledResponse from(NotificationSetting setting) {
		return NotificationEnabledResponse.builder()
			.notificationEnabled(setting.isNotificationEnabled()) // 엔티티의 현재 빵알람 여부 값 세팅
			.build();
	}

}
