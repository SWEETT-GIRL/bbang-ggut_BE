package sweetgirl.croissant.alarm.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.common.enums.DeviceOs;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class FcmService {

	final FcmTokenRepository fcmTokenRepository;
	final MemberRepository memberRepository;

	// 기기별 FCM Token 저장 또는 갱신
	public void saveOrUpdateFcmToken(Long memberId, FcmTokenRequest fcmTokenRequest) {

		Member member = memberRepository.findById(memberId).orElseThrow();

		String deviceId = fcmTokenRequest.getDeviceId();
		DeviceOs deviceOs = fcmTokenRequest.getDeviceOs();
		String token = fcmTokenRequest.getToken();

		fcmTokenRepository.findByMemberAndDeviceId(member, deviceId)
			.ifPresentOrElse(
				fcmToken -> fcmToken.updateToken(token),
				() -> fcmTokenRepository.save(FcmToken.builder()
					.member(member)
					.deviceId(deviceId)
					.deviceOs(deviceOs)
					.token(token)
					.build())

			);
	}

	// FCM Token 비활성화
	public void deactivateFcmToken(String fcmToken) {
		FcmToken token = fcmTokenRepository.findByToken(fcmToken)
			.orElseThrow(() -> new CustomException(AlarmErrorCode.FCM_TOKEN_NOT_FOUND));
		token.deactivate();
	}

	// Firebase에 푸시 메시지를 쏘는 메서드
	public void sendPushNotification(String fcmToken, String title, String body) {
		Message message = Message.builder()
			.setToken(fcmToken) // 어느 기기로 보낼지
			.setNotification(Notification.builder() // 뭘 보낼지 (제목 + 본문)
				.setTitle(title)
				.setBody(body)
				.build())
			.build();

		try {
			FirebaseMessaging.getInstance().send(message); // 전송
		} catch (FirebaseMessagingException e) {
			MessagingErrorCode errorCode = e.getMessagingErrorCode();
			if (errorCode == MessagingErrorCode.UNREGISTERED //토큰 만료되거나 앱 삭제됨
				|| errorCode == MessagingErrorCode.INVALID_ARGUMENT) { // 아니면 토큰 형식 잘못됨
				deactivateFcmToken(fcmToken);
			}
		}
	}

}
