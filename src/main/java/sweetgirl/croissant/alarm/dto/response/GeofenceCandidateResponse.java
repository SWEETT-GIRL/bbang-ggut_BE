package sweetgirl.croissant.alarm.dto.response;

import lombok.Builder;
import lombok.Getter;
import sweetgirl.croissant.store.repository.GeofenceCandidateProjection;

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

	public static GeofenceCandidateResponse from(GeofenceCandidateProjection projection, int radiusMeters) {
		return GeofenceCandidateResponse.builder()
			.storeId(projection.getStoreId())
			.name(projection.getStoreName())
			.latitude(projection.getLatitude())
			.longitude(projection.getLongitude())
			.radiusMeters(radiusMeters)
			.distanceMeters(projection.getDistanceMeters())
			.build();
	}

}
