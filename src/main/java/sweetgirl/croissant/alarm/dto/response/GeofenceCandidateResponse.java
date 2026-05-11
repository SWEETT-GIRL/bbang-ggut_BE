package sweetgirl.croissant.alarm.dto.response;

import lombok.Builder;
import lombok.Getter;

// 단일 가게 후보 - 앱이 OS Geofence에 등록할 가게 한 건의 정보
@Getter
@Builder
public class GeofenceCandidateResponse {

	Long storeId;
	String name;
	Double latitude;
	Double longitude;
	Integer radiusMeters;
	Double distanceMeters;

}
