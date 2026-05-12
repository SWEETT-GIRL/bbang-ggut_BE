package sweetgirl.croissant.alarm.dto.response;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

//후보 목록 wrapper - GeofenceCandidateResponse를 감쌈. (20개)
@Getter
@Builder
public class GeofenceCandidatesResponse {

	List<GeofenceCandidateResponse> stores;
}
