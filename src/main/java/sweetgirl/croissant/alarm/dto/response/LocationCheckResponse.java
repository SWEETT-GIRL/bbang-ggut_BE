package sweetgirl.croissant.alarm.dto.response;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LocationCheckResponse {
	boolean triggered;
	List<String> storeNames;
}
