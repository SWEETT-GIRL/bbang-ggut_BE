package sweetgirl.croissant.alarm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.alarm.enums.DeviceOs;

@Getter
@NoArgsConstructor
public class FcmTokenRequest {

	@NotBlank
	@Size(max = 200)
	String deviceId;

	@NotNull
	DeviceOs deviceOs;

	@NotBlank
	@Size(max = 4096)
	String token;

	@Size(max = 100)
	String appVersion;

	@Size(max = 100)
	String buildVersion;

	@Size(max = 200)
	String bundleId;

}
