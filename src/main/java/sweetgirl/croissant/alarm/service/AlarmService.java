package sweetgirl.croissant.alarm.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.alarm.repository.StoreNotificationRepository;
import sweetgirl.croissant.member.entity.Member;

@Service
@RequiredArgsConstructor
@Transactional
public class AlarmService {

	final StoreNotificationRepository storeNotificationRepository;

	@Transactional(readOnly = true)
	public List<StoreNotification> getStoreNotificationHistory(Member member) {
		// 조회 → stream map → toList
		return null;
	}

}
