package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;

import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

	@Mock
	FcmTokenRepository fcmTokenRepository;

	@Mock
	MemberReader memberReader;

	@InjectMocks
	FcmService fcmService;

	@Test
	@DisplayName("saveOrUpdateFcmToken: 기존 토큰 있으면 토큰값을 갱신한다")
	void saveOrUpdateFcmToken_updatesToken_whenTokenExists() {
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

		given(memberReader.getMember(1L)).willReturn(member);
		given(fcmTokenRepository.findByMemberAndDeviceId(member, "device-001"))
			.willReturn(Optional.of(existingToken));

		fcmService.saveOrUpdateFcmToken(1L, request);

		assertThat(existingToken.getToken()).isEqualTo("new-token");
		verify(fcmTokenRepository, never()).save(any());
	}

	@Test
	@DisplayName("saveOrUpdateFcmToken: 기존 토큰 없으면 새 토큰을 저장한다")
	void saveOrUpdateFcmToken_savesNewToken_whenTokenNotExists() {
		Member member = mock(Member.class);
		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-001");
		given(request.getDeviceOs()).willReturn(DeviceOs.ANDROID);
		given(request.getToken()).willReturn("new-token");

		given(memberReader.getMember(1L)).willReturn(member);
		given(fcmTokenRepository.findByMemberAndDeviceId(member, "device-001"))
			.willReturn(Optional.empty());

		fcmService.saveOrUpdateFcmToken(1L, request);

		ArgumentCaptor<FcmToken> captor = ArgumentCaptor.forClass(FcmToken.class);
		verify(fcmTokenRepository).save(captor.capture());
		assertThat(captor.getValue().getToken()).isEqualTo("new-token");
		assertThat(captor.getValue().getDeviceId()).isEqualTo("device-001");
	}

	@Test
	@DisplayName("saveOrUpdateFcmToken: 다른 member에 동일 token이 활성으로 있으면 비활성화한다")
	void saveOrUpdateFcmToken_deactivatesOtherMemberToken_whenSameTokenActive() {
		Member currentMember = mock(Member.class);

		Member otherMember = mock(Member.class);
		given(otherMember.getId()).willReturn(5L);

		FcmToken otherMemberToken = FcmToken.builder()
			.member(otherMember)
			.deviceId("device-shared")
			.deviceOs(DeviceOs.IOS)
			.token("shared-token")
			.build();

		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-shared");
		given(request.getDeviceOs()).willReturn(DeviceOs.IOS);
		given(request.getToken()).willReturn("shared-token");

		given(memberReader.getMember(10L)).willReturn(currentMember);
		given(fcmTokenRepository.findAllByTokenAndIsActiveTrue("shared-token"))
			.willReturn(List.of(otherMemberToken));
		given(fcmTokenRepository.findByMemberAndDeviceId(currentMember, "device-shared"))
			.willReturn(Optional.empty());

		fcmService.saveOrUpdateFcmToken(10L, request);

		assertThat(otherMemberToken.isActive()).isFalse();
		verify(fcmTokenRepository).save(any());
	}

	@Test
	@DisplayName("saveOrUpdateFcmToken: 동일 member의 활성 token row는 비활성화하지 않는다")
	void saveOrUpdateFcmToken_keepsOwnActiveToken() {
		Member currentMember = mock(Member.class);
		given(currentMember.getId()).willReturn(10L);

		FcmToken ownToken = FcmToken.builder()
			.member(currentMember)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("same-token")
			.build();

		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-001");
		given(request.getDeviceOs()).willReturn(DeviceOs.IOS);
		given(request.getToken()).willReturn("same-token");

		given(memberReader.getMember(10L)).willReturn(currentMember);
		given(fcmTokenRepository.findAllByTokenAndIsActiveTrue("same-token"))
			.willReturn(List.of(ownToken));
		given(fcmTokenRepository.findByMemberAndDeviceId(currentMember, "device-001"))
			.willReturn(Optional.of(ownToken));

		fcmService.saveOrUpdateFcmToken(10L, request);

		assertThat(ownToken.isActive()).isTrue();
		assertThat(ownToken.getToken()).isEqualTo("same-token");
	}

	@Test
	@DisplayName("deactivateFcmToken: 토큰이 존재하면 비활성화한다")
	void deactivateFcmToken_deactivatesToken_whenTokenExists() {
		Member member = mock(Member.class);
		FcmToken fcmToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("some-token")
			.build();

		given(fcmTokenRepository.findByToken("some-token")).willReturn(Optional.of(fcmToken));

		fcmService.deactivateFcmToken("some-token");

		assertThat(fcmToken.isActive()).isFalse();
	}

	@Test
	@DisplayName("deactivateFcmToken: 토큰이 없으면 FCM_TOKEN_NOT_FOUND 예외를 던진다")
	void deactivateFcmToken_throwsException_whenTokenNotFound() {
		given(fcmTokenRepository.findByToken("invalid-token")).willReturn(Optional.empty());

		assertThatThrownBy(() -> fcmService.deactivateFcmToken("invalid-token"))
			.isInstanceOf(CustomException.class)
			.satisfies(e -> assertThat(((CustomException)e).getErrorCode())
				.isEqualTo(AlarmErrorCode.FCM_TOKEN_NOT_FOUND));
	}

	@Test
	@DisplayName("sendPushNotification: 전송 성공 시 토큰을 비활성화하지 않는다")
	void sendPushNotification_doesNotDeactivate_whenSuccess() throws FirebaseMessagingException {
		FirebaseMessaging mockFirebaseMessaging = mock(FirebaseMessaging.class);

		try (MockedStatic<FirebaseMessaging> staticMock = mockStatic(FirebaseMessaging.class)) {
			staticMock.when(FirebaseMessaging::getInstance).thenReturn(mockFirebaseMessaging);
			given(mockFirebaseMessaging.send(any())).willReturn("message-id");

			fcmService.sendPushNotification("some-token", "제목", "내용", Map.of());

			verify(fcmTokenRepository, never()).findByToken(any());
		}
	}

	@Test
	@DisplayName("sendPushNotification: UNREGISTERED 에러 시 토큰을 비활성화한다")
	void sendPushNotification_deactivatesToken_whenUnregistered() throws FirebaseMessagingException {
		Member member = mock(Member.class);
		FcmToken fcmToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("some-token")
			.build();

		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.UNREGISTERED);

		FirebaseMessaging mockFirebaseMessaging = mock(FirebaseMessaging.class);

		try (MockedStatic<FirebaseMessaging> staticMock = mockStatic(FirebaseMessaging.class)) {
			staticMock.when(FirebaseMessaging::getInstance).thenReturn(mockFirebaseMessaging);
			given(mockFirebaseMessaging.send(any())).willThrow(exception);
			given(fcmTokenRepository.findByToken("some-token")).willReturn(Optional.of(fcmToken));

			fcmService.sendPushNotification("some-token", "제목", "내용", Map.of());

			assertThat(fcmToken.isActive()).isFalse();
		}
	}

	@Test
	@DisplayName("sendPushNotification: INVALID_ARGUMENT 에러 시 토큰을 비활성화한다")
	void sendPushNotification_deactivatesToken_whenInvalidArgument() throws FirebaseMessagingException {
		Member member = mock(Member.class);
		FcmToken fcmToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("bad-token")
			.build();

		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.INVALID_ARGUMENT);

		FirebaseMessaging mockFirebaseMessaging = mock(FirebaseMessaging.class);

		try (MockedStatic<FirebaseMessaging> staticMock = mockStatic(FirebaseMessaging.class)) {
			staticMock.when(FirebaseMessaging::getInstance).thenReturn(mockFirebaseMessaging);
			given(mockFirebaseMessaging.send(any())).willThrow(exception);
			given(fcmTokenRepository.findByToken("bad-token")).willReturn(Optional.of(fcmToken));

			fcmService.sendPushNotification("bad-token", "제목", "내용", Map.of());

			assertThat(fcmToken.isActive()).isFalse();
		}
	}

	@Test
	@DisplayName("sendPushNotification: 그 외 에러 시 토큰을 비활성화하지 않는다")
	void sendPushNotification_doesNotDeactivate_whenOtherError() throws FirebaseMessagingException {
		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.INTERNAL);

		FirebaseMessaging mockFirebaseMessaging = mock(FirebaseMessaging.class);

		try (MockedStatic<FirebaseMessaging> staticMock = mockStatic(FirebaseMessaging.class)) {
			staticMock.when(FirebaseMessaging::getInstance).thenReturn(mockFirebaseMessaging);
			given(mockFirebaseMessaging.send(any())).willThrow(exception);

			assertThatThrownBy(() -> fcmService.sendPushNotification("some-token", "제목", "내용", Map.of()))
				.isInstanceOf(AlarmException.class);

			verify(fcmTokenRepository, never()).findByToken(any());
		}
	}
}
