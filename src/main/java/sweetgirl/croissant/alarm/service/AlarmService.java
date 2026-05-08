package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;

@Service
@RequiredArgsConstructor
@Transactional
public class AlarmService {

	final StoreNotificationRepository storeNotificationRepository;

	@Transactional(readOnly = true)
	public List<StoreNotificationResponse> getStoreNotificationHistory(Member member) {

		return storeNotificationRepository.findByMemberAndSentAtAfterOrderBySentAtDesc(member,
			LocalDateTime.now().minusWeeks(1))
			.stream()
			.map(StoreNotificationResponse::from)
			.toList();
	}
}
