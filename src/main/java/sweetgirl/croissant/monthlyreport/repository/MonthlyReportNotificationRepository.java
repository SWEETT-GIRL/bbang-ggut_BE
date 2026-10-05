package sweetgirl.croissant.monthlyreport.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMemberId;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportNotification;

public interface MonthlyReportNotificationRepository
		extends JpaRepository<MonthlyReportNotification, MonthlyReportMemberId> {
	String STATUS_FROM = """
			FROM monthly_report_draft d
			LEFT JOIN monthly_report_member r ON r.member_id = d.member_id AND r.year_month = d.year_month
			LEFT JOIN monthly_report_notification e ON e.member_id = d.member_id AND e.year_month = d.year_month
			LEFT JOIN monthly_report_push p ON p.notification_id = e.notification_id
			LEFT JOIN notification_delivery nd ON nd.notification_id = e.notification_id
				AND nd.member_id = d.member_id AND nd.channel = 'PUSH'
			LEFT JOIN notification_recipient nr ON nr.notification_id = e.notification_id
				AND nr.member_id = d.member_id
			LEFT JOIN LATERAL (
				SELECT min(l.created_at) AT TIME ZONE 'Asia/Seoul' AS first_viewed_at
				FROM request_log l
				WHERE r.status = 'PUBLISHED' AND l.member_id = d.member_id
					AND l.http_method = 'GET' AND l.response_status = 200
					AND l.endpoint LIKE '/reports/monthly/%/me'
					AND l.endpoint = '/reports/monthly/' || d.year_month || '/me'
					AND l.created_at >= r.published_at AT TIME ZONE 'Asia/Seoul'
			) v ON true
			WHERE d.run_id = :runId
			""";
	String PUSH_STATE = """
			CASE WHEN r.status IS DISTINCT FROM 'PUBLISHED' THEN 'NOT_PUBLISHED'
			WHEN nd.status = 'SENT' THEN 'SENT'
			WHEN p.done THEN 'SKIPPED'
			WHEN p.attempts >= 5 THEN 'FAILED'
			WHEN p.attempts > 0 THEN 'RETRY_WAIT'
			WHEN p.notification_id IS NOT NULL THEN 'PENDING'
			WHEN e.notification_id IS NOT NULL THEN 'NOT_SCHEDULED' ELSE 'UNKNOWN' END
			""";

	@Modifying
	@Query(value = """
			INSERT INTO monthly_report_notification (member_id, year_month, notification_id)
			VALUES (:memberId, :month, :notificationId)
			ON CONFLICT (member_id, year_month) DO UPDATE SET notification_id = EXCLUDED.notification_id
			""", nativeQuery = true)
	int linkNotification(@Param("memberId") Long memberId, @Param("month") String month,
			@Param("notificationId") Long notificationId);

	@Query(value = """
			SELECT EXISTS (SELECT 1 FROM notification_delivery
			WHERE notification_id = :notificationId AND member_id = :memberId
			AND channel = 'PUSH' AND status = 'SENT')
			""", nativeQuery = true)
	boolean hasSuccessfulPush(@Param("notificationId") Long notificationId, @Param("memberId") Long memberId);

	@Query(value = "SELECT " + PUSH_STATE + " AS pushState, count(*) AS members, "
		+ "count(*) FILTER (WHERE r.status = 'PUBLISHED') AS published, "
		+ "count(*) FILTER (WHERE r.status = 'PUBLISHED' AND v.first_viewed_at IS NOT NULL) AS viewed, "
		+ "count(*) FILTER (WHERE nr.read_at IS NOT NULL) AS notificationOpened "
		+ STATUS_FROM + " GROUP BY 1", nativeQuery = true)
	List<Summary> summarize(@Param("runId") UUID runId);

	@Query(value = "SELECT d.member_id AS memberId, r.published_at AS publishedAt, "
		+ "v.first_viewed_at AS firstViewedAt, nr.read_at AT TIME ZONE 'Asia/Seoul' AS notificationReadAt, "
		+ PUSH_STATE
		+ " AS pushState, "
		+ "p.send_at AS sendAt, p.next_attempt_at AS nextAttemptAt, p.completed_at AS completedAt, "
		+ "COALESCE(p.total_attempts, 0) AS totalAttempts, COALESCE(p.manual_retries, 0) AS manualRetries, "
		+ "CASE WHEN nd.status = 'SENT' THEN NULL WHEN p.done THEN p.error_code "
		+ "WHEN p.attempts > 0 THEN COALESCE(nd.fail_reason, p.error_code) ELSE NULL END AS errorCode "
		+ STATUS_FROM + " ORDER BY d.member_id", nativeQuery = true)
	Slice<MemberStatus> status(@Param("runId") UUID runId, Pageable pageable);

	interface Summary {
		String getPushState();

		long getMembers();

		long getPublished();

		long getViewed();

		long getNotificationOpened();
	}

	interface MemberStatus {
		Long getMemberId();

		Instant getPublishedAt();

		Instant getFirstViewedAt();

		Instant getNotificationReadAt();

		String getPushState();

		Instant getSendAt();

		Instant getNextAttemptAt();

		Instant getCompletedAt();

		int getTotalAttempts();

		int getManualRetries();

		String getErrorCode();
	}
}
