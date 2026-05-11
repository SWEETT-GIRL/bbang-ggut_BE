package sweetgirl.croissant.alarm.dto.request;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
// 진입 이벤트 요청: OS가 Geofence enter 이벤트를 앱에 전달했을때, 앱이 서버로 보내는 body
public class GeofenceEnterRequest {

	@NotNull
	Long storeId;

	@NotNull
	Double latitude;

	@NotNull
	Double longitude;

	LocalDateTime occurredAt; // 클라이언트 이벤트 시각

}
