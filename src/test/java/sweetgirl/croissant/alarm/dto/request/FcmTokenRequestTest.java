package sweetgirl.croissant.alarm.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.alarm.enums.DeviceOs;

class FcmTokenRequestTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	@DisplayName("FCM 토큰 요청은 소문자 deviceOs도 역직렬화한다")
	void deserialize_acceptsLowercaseDeviceOs() throws Exception {
		String json = "{"
			+ "\"deviceId\":\"device-001\","
			+ "\"deviceOs\":\"ios\","
			+ "\"token\":\"fcm-token\""
			+ "}";

		FcmTokenRequest request = objectMapper.readValue(json, FcmTokenRequest.class);

		assertThat(request.getDeviceOs()).isEqualTo(DeviceOs.IOS);
	}

	@Test
	@DisplayName("FCM 토큰 요청은 255자를 넘는 토큰도 역직렬화한다")
	void deserialize_acceptsTokenLongerThan255Characters() throws Exception {
		String longToken = "a".repeat(300);
		String json = "{"
			+ "\"deviceId\":\"device-001\","
			+ "\"deviceOs\":\"ANDROID\","
			+ "\"token\":\"" + longToken + "\""
			+ "}";

		FcmTokenRequest request = objectMapper.readValue(json, FcmTokenRequest.class);

		assertThat(request.getToken()).hasSize(300);
	}
}
