package sweetgirl.croissant.alarm.service;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.FcmTokenRequest;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.enums.DeviceOs;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@RequiredArgsConstructor
@Transactional
public class FcmService {

	private static final Logger log = LoggerFactory.getLogger(FcmService.class);

	final FcmTokenRepository fcmTokenRepository;
	final MemberReader memberReader;

	// 기기별 FCM Token 저장 또는 갱신
	public void saveOrUpdateFcmToken(Long memberId, FcmTokenRequest fcmTokenRequest) {

		Member member = memberReader.getMember(memberId);

		String deviceId = fcmTokenRequest.getDeviceId();
		DeviceOs deviceOs = fcmTokenRequest.getDeviceOs();
		String token = fcmTokenRequest.getToken();

		// 동일 token을 가진 다른 row를 모두 비활성화 — (memberId, deviceId)가 정확히 일치하는 row만 활성으로 유지
		// 다른 member든 같은 member의 다른 deviceId든, 잔재된 활성 row가 중복 발송을 일으키지 않도록 정리
		fcmTokenRepository.findAllByTokenAndIsActiveTrue(token).stream()
			.filter(other -> !(other.getMember().getId().equals(memberId)
				&& other.getDeviceId().equals(deviceId)))
			.forEach(FcmToken::deactivate);

		try {
			fcmTokenRepository.findByMemberAndDeviceId(member, deviceId)
				.ifPresentOrElse(
					fcmToken -> fcmToken.updateToken(token),
					() -> fcmTokenRepository.save(FcmToken.builder()
						.member(member)
						.deviceId(deviceId)
						.deviceOs(deviceOs)
						.token(token)
						.build()));
		} catch (DataIntegrityViolationException e) {
			fcmTokenRepository.findByMemberAndDeviceId(member, deviceId)
				.ifPresent(fcmToken -> fcmToken.updateToken(token));
		}
	}

	// FCM Token 비활성화
	public void deactivateFcmToken(String fcmToken) {
		FcmToken token = fcmTokenRepository.findByToken(fcmToken)
			.orElseThrow(() -> new CustomException(AlarmErrorCode.FCM_TOKEN_NOT_FOUND));
		token.deactivate();
	}

	// FCM 메시지 TTL — 1시간. OS 가 throttle 해서 묵힌 알림이 한꺼번에 쏟아지는 현상 방지.
	private static final long FCM_TTL_MILLIS = 60L * 60 * 1000;

	// Firebase에 푸시 메시지를 쏘는 메서드
	// NOT_SUPPORTED: 부모 트랜잭션과 분리해 FCM 실패가 DB 트랜잭션을 rollback-only로 오염시키지 않도록
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void sendPushNotification(String fcmToken, String title, String body, Map<String, String> data) {
		// Android: HIGH priority 명시 (Doze 우회 시도) + 1시간 TTL.
		AndroidConfig androidConfig = AndroidConfig.builder()
			.setPriority(AndroidConfig.Priority.HIGH)
			.setTtl(FCM_TTL_MILLIS)
			.build();

		// iOS: apns-priority 10 (즉시 전달) + push-type alert + 1시간 만료.
		long apnsExpirationEpochSec = System.currentTimeMillis() / 1000 + (FCM_TTL_MILLIS / 1000);
		ApnsConfig apnsConfig = ApnsConfig.builder()
			.putHeader("apns-priority", "10")
			.putHeader("apns-push-type", "alert")
			.putHeader("apns-expiration", String.valueOf(apnsExpirationEpochSec))
			.build();

		Message message = Message.builder()
			.setToken(fcmToken) // 어느 기기로 보낼지
			.setNotification(Notification.builder() // 뭘 보낼지 (제목 + 본문)
				.setTitle(title)
				.setBody(body)
				.build())
			.putAllData(data)
			.setAndroidConfig(androidConfig)
			.setApnsConfig(apnsConfig)
			.build();

		try {
			FirebaseMessaging.getInstance().send(message);
		} catch (FirebaseMessagingException e) {
			MessagingErrorCode errorCode = e.getMessagingErrorCode();
			if (errorCode == MessagingErrorCode.UNREGISTERED
				|| errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
				deactivateFcmToken(fcmToken);
			} else {
				log.error("FCM 발송 실패 [token={}, errorCode={}]", fcmToken, errorCode, e);
				throw new AlarmException(AlarmErrorCode.FCM_SEND_FAILED);
			}
		}
	}

}
