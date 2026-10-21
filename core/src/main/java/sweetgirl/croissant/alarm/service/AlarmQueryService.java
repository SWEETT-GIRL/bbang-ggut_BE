package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidateResponse;
import sweetgirl.croissant.alarm.dto.response.GeofenceCandidatesResponse;
import sweetgirl.croissant.alarm.dto.response.MarketingEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.NotificationEnabledResponse;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlarmQueryService {

	private static final double CANDIDATE_RADIUS_METERS = 3000.0;

	private final StoreNotificationRepository storeNotificationRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MemberReader memberReader;
	private final NotificationSettingProvider notificationSettingProvider;

	// 빵알람 수신 여부 조회
	public NotificationEnabledResponse getNotificationEnabled(Long memberId) {
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId); // 알림 설정 조회, 없으면 예외
		return NotificationEnabledResponse.from(setting); // 현재 빵알람 여부를 DTO 로 변환해 반환
	}

	// 마케팅 알람 수신 여부 조회
	public MarketingEnabledResponse getMarketingEnabled(Long memberId) {
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId); // 알림 설정 조회, 없으면 예외
		return MarketingEnabledResponse.from(setting); // 현재 마케팅 알람 여부를 DTO 로 변환해 반환
	}

	// 알람 히스토리 목록 조회
	public List<StoreNotificationResponse> getStoreNotificationHistory(Long memberId) {
		Member member = memberReader.getMember(memberId);
		return storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(member,
			LocalDateTime.now().minusWeeks(1))
			.stream()
			.map(StoreNotificationResponse::from)
			.toList();
	}

	// Geofence에 등록할 구독 가게 후보 조회
	public GeofenceCandidatesResponse getGeofenceCandidates(Long memberId, double lat, double lng) {
		NotificationSetting setting = notificationSettingProvider.getOrCreate(memberId);
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
}
