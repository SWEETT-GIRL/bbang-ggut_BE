package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.response.StoreNotificationResponse;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.exception.AlarmException;
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

	public void markAsRead(Member member, Long alarmId) {

		StoreNotification notification = storeNotificationRepository.findById(alarmId)
			.orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));

		if (member != null && !notification.getMember().getId().equals(member.getId())) {
			throw new AlarmException(AlarmErrorCode.ALARM_FORBIDDEN);
		}
		notification.markAsRead();
	}

}
