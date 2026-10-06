package sweetgirl.croissant.monthlyreport.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 보고서에 발행한 기존 알림을 연결한다. 읽음·발송 결과는 기존 알림 테이블에서 조회한다. */
@Entity
@Table(name = "monthly_report_notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportNotification {
	@EmbeddedId
	private MonthlyReportMemberId id;
	@Column(name = "notification_id")
	private Long notificationId;
}
