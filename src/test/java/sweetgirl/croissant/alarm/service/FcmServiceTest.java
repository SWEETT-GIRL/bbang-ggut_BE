package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

	@Mock
	FcmTokenRepository fcmTokenRepository;

	@Mock
	MemberReader memberReader;

	/**
	 * 발송기를 목으로 끼운다. 예전에는 {@code FirebaseMessaging.getInstance()}를 static 목킹으로
	 * 가로채야 했는데, 서비스가 주입받는 쪽으로 바뀌면서 그럴 필요가 없어졌다.
	 */
	@Mock
	FirebaseMessaging firebaseMessaging;

	@InjectMocks
	FcmService fcmService;

	@Test
	@DisplayName("saveOrUpdateFcmToken: 기존 행 유무를 따지지 않고 업서트 한 문장으로 넣거나 갱신한다")
	void saveOrUpdateFcmToken_upsertsOnce() {
		Member member = mock(Member.class);
		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-001");
		given(request.getDeviceOs()).willReturn(DeviceOs.ANDROID);
		given(request.getToken()).willReturn("new-token");
		given(request.getAppVersion()).willReturn("2.0.0");
		given(request.getBuildVersion()).willReturn("43");
		given(request.getBundleId()).willReturn("com.bbanggut.app");
		given(memberReader.getMember(1L)).willReturn(member);

		fcmService.saveOrUpdateFcmToken(1L, request);

		verify(fcmTokenRepository).upsert(1L, "device-001", "ANDROID", "new-token", "2.0.0", "43", "com.bbanggut.app");
		verify(fcmTokenRepository, never()).save(any());
	}

	@Test
	@DisplayName("saveOrUpdateFcmToken: 없는 회원이면 예외가 나고 업서트는 부르지 않는다")
	void saveOrUpdateFcmToken_throws_whenMemberNotFound() {
		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(memberReader.getMember(99L)).willThrow(new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		assertThatThrownBy(() -> fcmService.saveOrUpdateFcmToken(99L, request))
			.isInstanceOf(MemberException.class);
		verify(fcmTokenRepository, never()).upsert(any(), any(), any(), any(), any(), any(), any());
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

		fcmService.saveOrUpdateFcmToken(10L, request);

		assertThat(otherMemberToken.isActive()).isFalse();
		verify(fcmTokenRepository).upsert(10L, "device-shared", "IOS", "shared-token", null, null, null);
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

		fcmService.saveOrUpdateFcmToken(10L, request);

		assertThat(ownToken.isActive()).isTrue();
		verify(fcmTokenRepository).upsert(10L, "device-001", "IOS", "same-token", null, null, null);
	}

	@Test
	@DisplayName("saveOrUpdateFcmToken: 동일 member여도 다른 deviceId의 동일 token row는 비활성화한다")
	void saveOrUpdateFcmToken_deactivatesSameMemberOtherDevice_whenSameTokenActive() {
		Member currentMember = mock(Member.class);
		given(currentMember.getId()).willReturn(10L);

		FcmToken oldDeviceToken = FcmToken.builder()
			.member(currentMember)
			.deviceId("device-old")
			.deviceOs(DeviceOs.IOS)
			.token("same-token")
			.build();

		FcmTokenRequest request = mock(FcmTokenRequest.class);
		given(request.getDeviceId()).willReturn("device-new");
		given(request.getDeviceOs()).willReturn(DeviceOs.IOS);
		given(request.getToken()).willReturn("same-token");

		given(memberReader.getMember(10L)).willReturn(currentMember);
		given(fcmTokenRepository.findAllByTokenAndIsActiveTrue("same-token"))
			.willReturn(List.of(oldDeviceToken));

		fcmService.saveOrUpdateFcmToken(10L, request);

		assertThat(oldDeviceToken.isActive()).isFalse();
		verify(fcmTokenRepository).upsert(10L, "device-new", "IOS", "same-token", null, null, null);
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

		given(fcmTokenRepository.findAllByToken("some-token")).willReturn(List.of(fcmToken));

		fcmService.deactivateFcmToken("some-token");

		assertThat(fcmToken.isActive()).isFalse();
	}

	@Test
	@DisplayName("deactivateFcmToken: 동일 토큰이 여러 row에 있으면 모두 비활성화한다")
	void deactivateFcmToken_deactivatesAllTokens_whenTokenDuplicated() {
		Member member = mock(Member.class);
		FcmToken firstToken = FcmToken.builder()
			.member(member)
			.deviceId("device-001")
			.deviceOs(DeviceOs.IOS)
			.token("some-token")
			.build();
		FcmToken secondToken = FcmToken.builder()
			.member(member)
			.deviceId("device-002")
			.deviceOs(DeviceOs.ANDROID)
			.token("some-token")
			.build();

		given(fcmTokenRepository.findAllByToken("some-token")).willReturn(List.of(firstToken, secondToken));

		fcmService.deactivateFcmToken("some-token");

		assertThat(firstToken.isActive()).isFalse();
		assertThat(secondToken.isActive()).isFalse();
	}

	@Test
	@DisplayName("deactivateFcmToken: 토큰이 없으면 FCM_TOKEN_NOT_FOUND 예외를 던진다")
	void deactivateFcmToken_throwsException_whenTokenNotFound() {
		given(fcmTokenRepository.findAllByToken("invalid-token")).willReturn(List.of());

		assertThatThrownBy(() -> fcmService.deactivateFcmToken("invalid-token"))
			.isInstanceOf(CustomException.class)
			.satisfies(e -> assertThat(((CustomException)e).getErrorCode())
				.isEqualTo(AlarmErrorCode.FCM_TOKEN_NOT_FOUND));
	}

	@Test
	@DisplayName("sendPushNotification: 전송 성공 시 토큰을 비활성화하지 않는다")
	void sendPushNotification_doesNotDeactivate_whenSuccess() throws FirebaseMessagingException {
		given(firebaseMessaging.send(any())).willReturn("message-id");

		fcmService.sendPushNotification("some-token", "제목", "내용", Map.of());

		verify(fcmTokenRepository, never()).findAllByToken(any());
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

		given(firebaseMessaging.send(any())).willThrow(exception);
		given(fcmTokenRepository.findAllByToken("some-token")).willReturn(List.of(fcmToken));

		fcmService.sendPushNotification("some-token", "제목", "내용", Map.of());

		assertThat(fcmToken.isActive()).isFalse();
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

		given(firebaseMessaging.send(any())).willThrow(exception);
		given(fcmTokenRepository.findAllByToken("bad-token")).willReturn(List.of(fcmToken));

		fcmService.sendPushNotification("bad-token", "제목", "내용", Map.of());

		assertThat(fcmToken.isActive()).isFalse();
	}

	@Test
	@DisplayName("sendPushNotification: THIRD_PARTY_AUTH_ERROR 에러 시 토큰을 비활성화하지 않는다")
	void sendPushNotification_doesNotDeactivate_whenThirdPartyAuthError() throws FirebaseMessagingException {
		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR);

		given(firebaseMessaging.send(any())).willThrow(exception);

		assertThatThrownBy(() -> fcmService.sendPushNotification("some-token", "제목", "내용", Map.of()))
			.isInstanceOf(AlarmException.class);

		verify(fcmTokenRepository, never()).findAllByToken(any());
	}

	@Test
	@DisplayName("sendPushNotification: 그 외 에러 시 토큰을 비활성화하지 않는다")
	void sendPushNotification_doesNotDeactivate_whenOtherError() throws FirebaseMessagingException {
		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		given(exception.getMessagingErrorCode()).willReturn(MessagingErrorCode.INTERNAL);

		given(firebaseMessaging.send(any())).willThrow(exception);

		assertThatThrownBy(() -> fcmService.sendPushNotification("some-token", "제목", "내용", Map.of()))
			.isInstanceOf(AlarmException.class);

		verify(fcmTokenRepository, never()).findAllByToken(any());
	}
}
