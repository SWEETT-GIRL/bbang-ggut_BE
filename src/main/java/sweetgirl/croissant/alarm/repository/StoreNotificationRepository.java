package sweetgirl.croissant.alarm.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.alarm.entity.StoreNotification;
import sweetgirl.croissant.member.entity.Member;

public interface StoreNotificationRepository extends JpaRepository<StoreNotification, Long> {
	List<StoreNotification> findByMemberOrderBySentAtDesc(Member member);
}
