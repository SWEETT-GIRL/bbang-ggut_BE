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
import sweetgirl.croissant.alarm.dto.request.GeofenceEnterRequest;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidateResponse;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidatesResponse;
import sweetgirl.croissant.alarm.dto.response.MarketingEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AlarmService {

	private static final Logger log = LoggerFactory.getLogger(AlarmService.class);
	private static final double CANDIDATE_RADIUS_METERS = 3000.0;

	final StoreNotificationRepository storeNotificationRepository;
	final NotificationSettingRepository notificationSettingRepository;
	final FcmTokenRepository fcmTokenRepository;
	final FcmService fcmService;
	final SubscribedStoreRepository subscribedStoreRepository;
	final MemberReader memberReader;
	final StoreRepository storeRepository;

	// 빵알람 수신 여부 조회
	@Transactional(readOnly = true) // 조회만 하므로 readOnly
	public NotificationEnabledResponse getNotificationEnabled(Long memberId) {
		NotificationSetting setting = getNotificationSetting(memberId); // 알림 설정 조회, 없으면 예외
		return NotificationEnabledResponse.from(setting); // 현재 빵알람 여부를 DTO 로 변환해 반환
	}

	// 빵알람 수신 여부 토글 — 현재 값을 반전시키고 변경된 상태 반환
	public NotificationEnabledResponse toggleNotificationEnabled(Long memberId) {
		NotificationSetting setting = getNotificationSetting(memberId); // 알림 설정 조회, 없으면 예외
		setting.toggleNotificationEnabled(); // true → false 또는 false → true 로 반전
		return NotificationEnabledResponse.from(setting); // 변경된 빵알람 여부를 DTO 로 반환
	}

	// 마케팅 알람 수신 여부 조회
	@Transactional(readOnly = true) // 조회만 하므로 readOnly
	public MarketingEnabledResponse getMarketingEnabled(Long memberId) {
		NotificationSetting setting = getNotificationSetting(memberId); // 알림 설정 조회, 없으면 예외
		return MarketingEnabledResponse.from(setting); // 현재 마케팅 알람 여부를 DTO 로 변환해 반환
	}

	// 마케팅 알람 수신 여부 토글 — 현재 값을 반전시키고 변경된 상태 반환
	public MarketingEnabledResponse toggleMarketingEnabled(Long memberId) {
		NotificationSetting setting = getNotificationSetting(memberId); // 알림 설정 조회, 없으면 예외
		setting.toggleMarketingEnabled(); // true → false 또는 false → true 로 반전
		return MarketingEnabledResponse.from(setting); // 변경된 마케팅 알람 여부를 DTO 로 반환
	}

	// 알람 히스토리 목록 조회
	@Transactional(readOnly = true)
	public List<StoreNotificationResponse> getStoreNotificationHistory(Long memberId) {
		Member member = memberReader.getMember(memberId);
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
		LocalTime now = LocalTime.now(zoneId);
		LocalTime start = setting.getStartTime();
		LocalTime end = setting.getEndTime();

		boolean isOutsideAllowedTime;
		if (start.isBefore(end)) {
			isOutsideAllowedTime = now.isBefore(start) || now.isAfter(end);
		} else {
			isOutsideAllowedTime = now.isBefore(start) && now.isAfter(end);
		}

		if (isOutsideAllowedTime) {
			return true;
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

	// FCM 알림 발송 + 이력 저장 — 하나라도 성공했을 때만 저장 (전부 실패 시 당일 재시도 가능)
	private void sendStoreNotification(Member member, Store store, List<FcmToken> tokens) {
		if (tokens.isEmpty()) { // 토큰 있는지 확인
			return;
		}
		String title = "빵집 알림";
		String body = store.getName() + " 근처에 있어요! 지금 방문해보세요";

		boolean anySent = false;

		for (FcmToken fcmToken : tokens) { // 등록된 모든 기기의 토큰에게 발송
			try {
				fcmService.sendPushNotification(fcmToken.getToken(), title, body);
				anySent = true;
			} catch (AlarmException e) {
				log.warn("FCM 발송 실패 [memberId={}, storeId={}, token={}]",
					member.getId(), store.getId(), fcmToken.getToken());
			}
		}
		// 알림 발송 이력 저장
		if (anySent) {
			storeNotificationRepository.save(StoreNotification.of(member, store, body));
		}

	}

	// Geofence에 등록할 구독 가게 후보 조회
	@Transactional(readOnly = true)
	public GeofenceCandidatesResponse getGeofenceCandidates(Long memberId, double lat, double lng) {
		NotificationSetting setting = getNotificationSetting(memberId);
		if (!setting.isNotificationEnabled()) {
			return GeofenceCandidatesResponse.builder().stores(List.of()).build();
		}

		int radiusMeters = setting.getNotificationRadius().getValue();

		List<GeofenceCandidateResponse> stores = subscribedStoreRepository.findGeofenceCandidates(memberId, lat, lng,
			CANDIDATE_RADIUS_METERS)
			.stream()
			.map(p -> GeofenceCandidateResponse.from(p, radiusMeters))
			.toList();

		return GeofenceCandidatesResponse.builder().stores(stores).build();

	}

	// Geofence 진입 이벤트 처리 — 클라이언트 OS가 enter 이벤트를 받으면 서버에서 재검증 후 FCM 발송
	@Transactional
	public void handleGeofenceEnter(Long memberId, GeofenceEnterRequest request) {

		NotificationSetting setting = getNotificationSetting(memberId); // 알림 설정 조회 - 제한 있으면 silent skip
		if (isNotificationBlocked(setting)) {
			return;
		}

		Store store = storeRepository.findById(request.getStoreId())
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
		Member member = memberReader.getMember(memberId);
		int radius = setting.getNotificationRadius().getValue();

		// 구독 여부 확인 + 요청 좌표가 사용자 반경 안인지 POSTGIS로 재검증 (OS 위변조 방지)
		boolean valid = subscribedStoreRepository.existsSubscribedStoreWithinRadius(memberId, request.getStoreId(),
			request.getLatitude(), request.getLongitude(), radius);

		if (!valid) {
			throw new AlarmException(AlarmErrorCode.GEOFENCE_NOT_SUBSCRIBED);
		}
		if (alreadyNotified(member, store)) { // 오늘 이미 이 가게에 알림을 보냈으면 중복 발송 방지 (silent skip)
			return;
		}
		List<FcmToken> tokens = fcmTokenRepository.findByMemberAndIsActive(member, true);
		sendStoreNotification(member, store, tokens);

	}

	private NotificationSetting getNotificationSetting(Long memberId) {
		if (memberId == null) {
			throw new CustomException(GeneralErrorCode._UNAUTHORIZED);
		}
		return notificationSettingRepository.findByMemberId(memberId)
			.orElseGet(() -> {
				Member member = memberReader.getMember(memberId);
				return notificationSettingRepository.save(
					NotificationSetting.builder()
						.member(member)
						.startTime(LocalTime.of(8, 0))
						.endTime(LocalTime.of(21, 0))
						.daysOfWeek("")
						.notificationRadius(NotificationRadius.R500)
						.isMarketingEnabled(false)
						.isNotificationEnabled(false)
						.build());
			});
	}

}
