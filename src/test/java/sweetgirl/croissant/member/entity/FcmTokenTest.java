package sweetgirl.croissant.member.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.common.enums.DeviceOs;

class FcmTokenTest {

	@Test
	@DisplayName("updateToken: 토큰값을 새값으로 갱신한다")
	void updateToken_updatesTokenValue() {
		Member member = mock(Member.class);
		FcmToken fcmToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("old-token")
			.build();

		fcmToken.updateToken("new-token");

		assertThat(fcmToken.getToken()).isEqualTo("new-token");
	}

	@Test
	@DisplayName("updateToken: isActive를 true로 설정한다")
	void updateToken_setsIsActiveToTrue() {
		Member member = mock(Member.class);
		FcmToken fcmToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.ANDROID)
			.token("old-token")
			.build();

		fcmToken.updateToken("new-token");

		assertThat(fcmToken.isActive()).isTrue();
	}
}
