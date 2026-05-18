package sweetgirl.croissant.alarm.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class FcmTokenDeleteRequest {

	@NotBlank
	@Size(max = 4096)
	String token;

}
