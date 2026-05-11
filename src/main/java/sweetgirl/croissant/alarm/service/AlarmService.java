package sweetgirl.croissant.alarm.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AlarmService {

	private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

	final StoreNotificationRepository storeNotificationRepository;
	final NotificationSettingRepository notificationSettingRepository;
	final FcmTokenRepository fcmTokenRepository;
	final FcmService fcmService;
	final SubscribedStoreRepository subscribedStoreRepository;
	final MemberRepository memberRepository;

	// 알람 히스토리 목록 조회
	@Transactional(readOnly = true)
	public List<StoreNotificationResponse> getStoreNotificationHistory(Long memberId) {
		Member member = getMember(memberId);
		return storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(member,
			LocalDateTime.now().minusWeeks(1))
			.stream()
			.map(StoreNotificationResponse::from)
			.toList();
	}

	// 알람 읽음 처리
	public void markAsRead(Long memberId, Long alarmId) {

		StoreNotification notification = storeNotificationRepository.findById(alarmId)
			.orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));

		if (memberId != null && !notification.getMember().getId().equals(memberId)) {
			throw new AlarmException(AlarmErrorCode.ALARM_FORBIDDEN);
		}
		notification.markAsRead();
	}

	// 알람 보내도 되는지 설정 보고 확인
	private boolean isNotificationBlocked(NotificationSetting setting) {
		// 1. 전체 알림 꺼짐
		if (!setting.isNotificationEnabled()) {
			return true;
		}

		// 서버 시스템 시간이 아닌 명시적 타임존 사용 (한국 서비스 기준)
		ZoneId zoneId = ZoneId.of("Asia/Seoul");

		// 2. 시간 제한 체크
		if (setting.isTimeRestricted()) {
			LocalTime now = LocalTime.now(zoneId);
			LocalTime start = setting.getStartTime();
			LocalTime end = setting.getEndTime();

			boolean isOutsideAllowedTime;
			if (start.isBefore(end)) {
				// 일반적인 경우 (예: 09:00 ~ 18:00)
				isOutsideAllowedTime = now.isBefore(start) || now.isAfter(end);
			} else {
				// 자정을 넘기는 경우 (예: 22:00 ~ 06:00)
				// 시작 시간 이전 '이고' 종료 시간 이후이면 차단
				isOutsideAllowedTime = now.isBefore(start) && now.isAfter(end);
			}

			if (isOutsideAllowedTime) {
				return true;
			}
		}

		// 3. 요일 제한 체크
		String today = LocalDate.now(zoneId).getDayOfWeek().name();
		return !setting.getDaysOfWeek().contains(today);
	}

	// 오늘 이 가게 이미 알림 보냈는지 중복 확인
	private boolean alreadyNotified(Member member, Store store) {
		LocalDateTime todayStart = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(); // 오늘 자정
		return storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(member, store, todayStart);
	}

	//FCM 알림 발송 + 이력 저장
	private void sendStoreNotification(Member member, Store store, List<FcmToken> tokens) {
		if (tokens.isEmpty()) { // 토큰 있는지 확인
			return;
		}
		String title = "빵집 알림";
		String body = store.getName() + " 근처에 있어요! 지금 방문해보세요";

		for (FcmToken fcmToken : tokens) { // 등록된 모든 기기의 토큰에게 발송
			try {
				fcmService.sendPushNotification(fcmToken.getToken(), title, body);
			} catch (AlarmException e) {
				log.warn("FCM 발송 실패 [memberId={}, storeId={}, token={}]",
					member.getId(), store.getId(), fcmToken.getToken());
			}
		}
		// 알림 발송 이력 저장
		storeNotificationRepository.save(StoreNotification.of(member, store, body));

	}

	private Member getMember(Long memberId) {
		return memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
	}

}
