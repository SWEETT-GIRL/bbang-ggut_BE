package sweetgirl.croissant.alarm.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class LocationCheckRequest {

	@NotNull
	Double latitude;

	@NotNull
	Double longitude;
}
