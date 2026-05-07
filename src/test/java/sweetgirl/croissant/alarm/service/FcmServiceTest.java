package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.common.enums.DeviceOs;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

	@Mock
	FcmTokenRepository fcmTokenRepository;

	@Mock
	MemberRepository memberRepository;

	@InjectMocks
	FcmService fcmService;

	@Test
	@DisplayName("saveOrUpdateToken: 기존 토큰 있으면 토큰값을 갱신한다")
	void saveOrUpdateToken_updatesToken_whenTokenExists() {
		Member member = mock(Member.class);
		FcmToken existingToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("old-token")
			.build();

		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-001");
		given(request.getDeviceOs()).willReturn(DeviceOs.IOS);
		given(request.getToken()).willReturn("new-token");

		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(fcmTokenRepository.findByMemberAndDeviceId(member, "device-001"))
			.willReturn(Optional.of(existingToken));

		fcmService.saveOrUpdateToken(1L, request);

		assertThat(existingToken.getToken()).isEqualTo("new-token");
		verify(fcmTokenRepository, never()).save(any());
	}

	@Test
	@DisplayName("saveOrUpdateToken: 기존 토큰 없으면 새 토큰을 저장한다")
	void saveOrUpdateToken_savesNewToken_whenTokenNotExists() {
		Member member = mock(Member.class);
		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-001");
		given(request.getDeviceOs()).willReturn(DeviceOs.ANDROID);
		given(request.getToken()).willReturn("new-token");

		given(memberRepository.findById(1L)).willReturn(Optional.of(member));
		given(fcmTokenRepository.findByMemberAndDeviceId(member, "device-001"))
			.willReturn(Optional.empty());

		fcmService.saveOrUpdateToken(1L, request);

		ArgumentCaptor<FcmToken> captor = ArgumentCaptor.forClass(FcmToken.class);
		verify(fcmTokenRepository).save(captor.capture());
		assertThat(captor.getValue().getToken()).isEqualTo("new-token");
		assertThat(captor.getValue().getDeviceId()).isEqualTo("device-001");
	}
}
