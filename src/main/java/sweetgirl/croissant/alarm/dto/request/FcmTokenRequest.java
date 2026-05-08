package sweetgirl.croissant.alarm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.common.enums.DeviceOs;

@Getter
@NoArgsConstructor
public class FcmTokenRequest {

	@NotBlank
	String deviceId;

	@NotNull
	DeviceOs deviceOs;

	@NotBlank
	String token;

}
