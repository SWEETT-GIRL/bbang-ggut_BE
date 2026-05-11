package sweetgirl.croissant.alarm.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.LocationCheckRequest;
import sweetgirl.croissant.alarm.dto.response.LocationCheckResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.SubscribedStore;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AlarmService {

	final StoreNotificationRepository storeNotificationRepository;
	final NotificationSettingRepository notificationSettingRepository;
	final FcmTokenRepository fcmTokenRepository;
	final FcmService fcmService;
	final SubscribedStoreRepository subscribedStoreRepository;

	// 알람 히스토리 목록 조회
	@Transactional(readOnly = true)
	public List<StoreNotificationResponse> getStoreNotificationHistory(Member member) {

		return storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(member,
			LocalDateTime.now().minusWeeks(1))
			.stream()
			.map(StoreNotificationResponse::from)
			.toList();
	}

	// 알람 읽음 처리
	public void markAsRead(Member member, Long alarmId) {

		StoreNotification notification = storeNotificationRepository.findById(alarmId)
			.orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));

		if (member != null && !notification.getMember().getId().equals(member.getId())) {
			throw new AlarmException(AlarmErrorCode.ALARM_FORBIDDEN);
		}
		notification.markAsRead();
	}

	// 같은 날 자정 이후 발송 기록이 있으면 skip -- 중복 방지 처리
	@Transactional(readOnly = true)
	public boolean isDuplicateAlarm(Member member, Store store) {
		LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
		return storeNotificationRepository.existsByMemberAndStoreAndSentAtAfter(member, store, startOfToday);
	}

	// 위치 기반 푸시 알림 트리거 — 현재 위치가 구독 빵집 근처이면 FCM 발송
	public LocationCheckResponse checkProximity(Member member, LocationCheckRequest request) {

		// 1. 알림 설정 조회
		NotificationSetting setting = notificationSettingRepository.findByMember(member)
			.orElseThrow(() -> new CustomException(AlarmErrorCode.ALARM_NOT_FOUND));

		// 2. 알림 차단 조건(ON/OFF, DND, 시간, 요일) 체크
		if (isNotificationBlocked(setting)) {
			return LocationCheckResponse.builder().triggered(false).storeNames(List.of()).build();
		}

		// 3. PostGIS ST_DWithin으로 반경 내 구독 가게 조회
		List<SubscribedStore> nearby = subscribedStoreRepository.findNearbySubscribedStores(
			member.getId(),
			request.getLatitude(),
			request.getLongitude(),
			setting.getNotificationRadius());

		// 4. 멤버의 활성 FCM 토큰 목록 조회 (기기별 발송용)
		List<FcmToken> tokens = fcmTokenRepository.findByMemberAndIsActive(member, true);

		// 5. 가게별 중복 체크 → FCM 발송 → 히스토리 저장
		List<String> notified = sendNotificationsIfEligible(member, nearby, tokens);

		return LocationCheckResponse.builder()
			.triggered(!notified.isEmpty())
			.storeNames(notified)
			.build();
	}

	// ON/OFF + DND + 허용 시간대 + 허용 요일 조건을 통합 체크 — 하나라도 걸리면 차단
	private boolean isNotificationBlocked(NotificationSetting setting) {

		if (!setting.isNotificationEnabled()) {
			return true;
		}

		if (!setting.isDndEnabled()) {
			return false;
		}

		LocalTime now = LocalTime.now();
		boolean inTime = !now.isBefore(setting.getStartTime()) && !now.isAfter(setting.getEndTime());

		//"'월','화','수'" — contains로 오늘 요일 포함 여부 확인
		String todayKr = toKoreanDay(LocalDate.now().getDayOfWeek());
		boolean inDay = setting.getDaysOfWeek().contains(todayKr);

		return !inTime || !inDay;
	}

	// 반경 내 가게 중 오늘 미발송 가게에 FCM 발송 + StoreNotification 저장
	private List<String> sendNotificationsIfEligible(Member member, List<SubscribedStore> nearby,
			List<FcmToken> tokens) {

		List<String> notified = new ArrayList<>();

		for (SubscribedStore ss : nearby) {
			Store store = ss.getStore();

			// 오늘 이미 이 가게 알림 발송했으면 skip
			if (isDuplicateAlarm(member, store)) {
				continue;
			}

			String message = store.getName() + " 근처에 있어요!";

			// 멤버의 모든 활성 기기에 FCM 발송
			for (FcmToken token : tokens) {
				fcmService.sendPushNotification(token.getToken(), store.getName(), message);
			}

			// 발송 기록 저장 — readAt=null이면 읽지 않은 상태
			storeNotificationRepository.save(StoreNotification.builder()
				.member(member)
				.store(store)
				.message(message)
				.sentAt(LocalDateTime.now())
				.build());

			notified.add(store.getName());
		}

		return notified;
	}

	// DayOfWeek → 한국어 요일 변환 ("월", "화", ...)
	private String toKoreanDay(DayOfWeek day) {
		return switch (day) {
			case MONDAY -> "월";
			case TUESDAY -> "화";
			case WEDNESDAY -> "수";
			case THURSDAY -> "목";
			case FRIDAY -> "금";
			case SATURDAY -> "토";
			case SUNDAY -> "일";
		};
	}

}
