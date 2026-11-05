package sweetgirl.croissant.alarm.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.request.CommunityNotificationSettingUpdateRequest;
import sweetgirl.croissant.alarm.dto.request.GeofenceEnterRequest;
import sweetgirl.croissant.alarm.dto.response.CommunityNotificationSettingResponse;
import sweetgirl.croissant.alarm.dto.response.MarketingEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationEnabledResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AlarmCommandService {

	private static final Logger log = LoggerFactory.getLogger(AlarmCommandService.class);

	private final StoreNotificationRepository storeNotificationRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final FcmService fcmService;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MemberReader memberReader;
	private final StoreRepository storeRepository;
	private final MeterRegistry meterRegistry;
	private final NotificationSettingProvider notificationSettingProvider;

	// 빵알람 수신 여부 토글 — 현재 값을 반전시키고 변경된 상태 반환
	public NotificationEnabledResponse toggleNotificationEnabled(Long memberId) {
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId); // 알림 설정 조회, 없으면 예외
		setting.toggleNotificationEnabled(); // true → false 또는 false → true 로 반전
		return NotificationEnabledResponse.from(setting); // 변경된 빵알람 여부를 DTO 로 반환
	}

	/** 팔로우 · 톡 · 좋아요 · 댓글 · 팔로잉 소식 스위치 — 바뀌는 키만 받는다. */
	public CommunityNotificationSettingResponse updateCommunitySettings(Long memberId,
			CommunityNotificationSettingUpdateRequest request) {
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId);
		setting.updateCommunitySwitches(request.follow(), request.pat(), request.like(), request.comment(),
			request.followingRecord());
		return CommunityNotificationSettingResponse.from(setting);
	}

	// 마케팅 알람 수신 여부 토글 — 현재 값을 반전시키고 변경된 상태 반환
	public MarketingEnabledResponse toggleMarketingEnabled(Long memberId) {
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId); // 알림 설정 조회, 없으면 예외
		setting.toggleMarketingEnabled(); // true → false 또는 false → true 로 반전
		// 설정 화면의 토글도 마케팅 수신 동의다 — 동의 기록(동의 여부·동의 시각)을 같은 값으로 남긴다
		memberReader.getMember(memberId).updateMarketingAgreement(setting.isMarketingEnabled());
		return MarketingEnabledResponse.from(setting); // 변경된 마케팅 알람 여부를 DTO 로 반환
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

	// Geofence 진입 이벤트 처리 — 클라이언트 OS가 enter 이벤트를 받으면 서버에서 재검증 후 FCM 발송
	public void handleGeofenceEnter(Long memberId, GeofenceEnterRequest request) {
		log.info("geofenceEnter: 진입 요청 [memberId={}, storeId={}, lat={}, lng={}]",
			memberId, request.getStoreId(), request.getLatitude(), request.getLongitude());

		// 알림 설정 조회 - 제한 있으면 silent skip
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId);
		if (isNotificationBlocked(setting)) {
			log.info("geofenceEnter: 알림 차단 (시간/요일 설정) [memberId={}]", memberId);
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
			log.info("geofenceEnter: 구독 미등록 또는 반경 외 [memberId={}, storeId={}]", memberId, request.getStoreId());
			throw new AlarmException(AlarmErrorCode.GEOFENCE_NOT_SUBSCRIBED);
		}

		// (memberId, storeId) 단위 동시 요청을 트랜잭션 advisory lock 으로 직렬화.
		// iOS region monitoring 의 enter 이벤트가 짧은 간격으로 2~5회 발화해 BE 에 동시 도달하는 케이스 방어.
		// 잠금 실패 → 다른 트랜잭션이 같은 키로 처리 중 → silent skip.
		long lockKey = (memberId << 32) | (store.getId() & 0xFFFFFFFFL);
		if (!storeNotificationRepository.tryAdvisoryXactLock(lockKey)) {
			log.info("geofenceEnter: 동시 요청 차단 (advisory lock) [memberId={}, storeId={}]",
				memberId, store.getId());
			return;
		}

		if (alreadyNotified(member, store)) { // 오늘 이미 이 가게에 알림을 보냈으면 중복 발송 방지 (silent skip)
			log.info("geofenceEnter: 오늘 이미 알림 발송됨 [memberId={}, storeId={}]", memberId, store.getId());
			return;
		}
		List<FcmToken> tokens = fcmTokenRepository.findByMemberAndIsActive(member, true);
		log.info("geofenceEnter: FCM 발송 시작 [memberId={}, storeId={}, tokenCount={}]",
			memberId, store.getId(), tokens.size());
		sendStoreNotification(member, store, tokens);
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
			log.info("geofenceEnter: FCM 토큰 없음 [memberId={}, storeId={}]", member.getId(), store.getId());
			return;
		}
		String title = "빵집 알림";
		String body = store.getName() + " 근처에 있어요! 지금 방문해보세요";
		Map<String, String> data = Map.of(
			"type", "GEOFENCE_ENTER",
			"storeId", String.valueOf(store.getId()),
			"screen", "bakeryDetail");

		// 같은 디바이스 다중 row(legacy 데이터)에 의한 중복 발송 방지 — 발송 직전 토큰 문자열 기준 dedup
		List<String> uniqueTokens = tokens.stream()
			.map(FcmToken::getToken)
			.distinct()
			.toList();

		boolean anySent = false;

		for (String token : uniqueTokens) { // 등록된 모든 기기의 토큰에게 발송
			try {
				fcmService.sendPushNotification(token, title, body, data);
				anySent = true;

				// 가게 id·이름은 라벨로 쓰지 않는다 — 가게가 5만이라 시계열이 그만큼 늘어난다.
				// 어느 가게였는지는 StoreNotification 행에 남으므로 메트릭은 종류별 건수만 센다
				meterRegistry.counter("push.sent", "type", "geofence_enter").increment();
			} catch (AlarmException e) {
				log.warn("FCM 발송 실패 [memberId={}, storeId={}, token={}]",
					member.getId(), store.getId(), maskToken(token));
			}
		}
		// 알림 발송 이력 저장
		if (anySent) {
			storeNotificationRepository.save(StoreNotification.of(member, store, body));
		}
	}

	private String maskToken(String token) {
		if (token == null || token.length() <= 12) {
			return "***";
		}
		return token.substring(0, 6) + "..." + token.substring(token.length() - 6);
	}
}
