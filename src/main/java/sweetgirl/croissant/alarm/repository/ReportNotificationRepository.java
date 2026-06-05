package sweetgirl.croissant.alarm.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import sweetgirl.croissant.alarm.entity.ReportNotification;

public interface ReportNotificationRepository extends JpaRepository<ReportNotification, Long> {

	List<ReportNotification> findByMemberIdOrderBySentAtDesc(Long memberId);
}
