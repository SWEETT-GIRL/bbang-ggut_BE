package sweetgirl.croissant.alarm.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.response.ReportNotificationResponse;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.entity.NotificationRecipient;
import sweetgirl.croissant.alarm.enums.NotificationTargetType;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.event.PushRequestedEvent;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.NotificationRecipientRepository;
import sweetgirl.croissant.comment.repository.BreadRecordCommentRepository;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

/**
 * 제보·신고·제재 결과 알림. 문구를 서버가 들고 다른 알림과 같은 알림함에 쌓인다.
 *
 * <p>예전에는 report_notification이라는 전용 테이블과 전용 조회 API를 갖고 있었다. 알림함이 둘로
 * 갈려 있을 이유가 없고, 푸시 파이프라인을 두 벌 만들 이유는 더 없어서 통합 알림으로 옮겼다.
 * 밖으로 나가는 응답 모양은 그대로다 — 앱이 이 필드들로 이의제기 화면을 연다.
 *
 * <p>reportId 계열은 payload에 담는다. 통합 테이블에 타입별 컬럼을 펼치면 대부분의 행에서 NULL인
 * 자리가 쌓이는데, 조건으로 걸어 찾을 일이 없는 값이라 한 덩어리로 두는 편이 낫다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ReportNotificationService {

	private static final Logger log = LoggerFactory.getLogger(ReportNotificationService.class);
	private static final String ABUSE_REPORT_RESULT_TITLE = "신고 검토가 완료되었습니다.";
	private static final String ABUSE_REPORT_RESULT_MESSAGE = "운영 정책에 따라 필요한 조치를 진행했습니다.";
	private static final String MODERATION_ACTION_TITLE = "운영 정책을 어겨 콘텐츠가 제한됐어요";
	private static final String MODERATION_RECORD_TITLE = "운영 정책을 어겨 빵로그가 숨겨졌어요";
	private static final String MODERATION_COMMENT_TITLE = "운영 정책을 어겨 댓글이 숨겨졌어요";
	private static final String MODERATION_ACTION_MESSAGE = "결정에 동의하지 않으면 30일 안에 이의제기할 수 있어요";

	private final NotificationRecipientRepository recipientRepository;
	private final NotificationRecorder notificationRecorder;
	private final ApplicationEventPublisher eventPublisher;
	private final BreadRecordCommentRepository commentRepository;

	/**
	 * 제보 승인·반려 결과를 제보자에게 알린다.
	 *
	 * <p>member가 null인 경우(익명 메뉴 추가 제보 등) 보낼 대상이 없으므로 조용히 넘어간다.
	 */
	public void notifyContributionReportResult(Member member, Store store, String fallbackStoreName,
			Long reportId, Long contributionReportId, ReportKind reportKind,
			ReportNotificationAction action, String subjectLabel) {
		if (member == null) {
			log.info("notifyReportResult: 제보자 없음 [reportId={}, kind={}]", reportId, reportKind);
			return;
		}

		boolean approved = action == ReportNotificationAction.APPROVED;
		String title = approved ? "제보가 반영되었어요" : "제보가 반려되었어요";
		String storeName = store != null ? store.getName() : fallbackStoreName(fallbackStoreName);
		String body = approved
			? String.format("%s의 %s 제보가 승인되었어요 🎉", storeName, subjectLabel)
			: String.format("%s의 %s 제보가 반려되었어요", storeName, subjectLabel);

		Map<String, String> payload = new HashMap<>();
		payload.put("reportId", String.valueOf(reportId));
		payload.put("reportKind", reportKind.name());
		payload.put("action", action.name());
		putIfNotNull(payload, "contributionReportId", contributionReportId);

		deliver(NotificationType.CONTRIBUTION_RESULT, member, title, body, store, storeName, payload);
	}

	public void notifyAbuseReportResult(AbuseReport report) {
		if (report == null || report.getReporter() == null) {
			log.info("notifyAbuseReportResult: 신고자 없음");
			return;
		}

		Map<String, String> payload = new HashMap<>();
		payload.put("reportId", String.valueOf(report.getId()));
		payload.put("abuseReportId", String.valueOf(report.getId()));
		payload.put("reportKind", ReportKind.ABUSE_REPORT.name());
		payload.put("action", ReportNotificationAction.ABUSE_REPORT_RESULT.name());

		deliver(NotificationType.ABUSE_REPORT_RESULT, report.getReporter(),
			ABUSE_REPORT_RESULT_TITLE, ABUSE_REPORT_RESULT_MESSAGE, null, null, payload);
	}

	public void notifyModerationAction(ModerationAction moderationAction) {
		if (moderationAction == null || moderationAction.getMember() == null) {
			log.info("notifyModerationAction: 피조치 회원 없음");
			return;
		}
		if (moderationAction.getReport() == null) {
			log.info("notifyModerationAction: 신고 기반 조치가 아니므로 알림 생략 [actionId={}]",
				moderationAction.getId());
			return;
		}

		Long reportId = moderationAction.getReport().getId();
		Map<String, String> payload = new HashMap<>();
		payload.put("reportId", String.valueOf(reportId));
		payload.put("abuseReportId", String.valueOf(reportId));
		payload.put("reportKind", ReportKind.ABUSE_REPORT.name());
		payload.put("action", ReportNotificationAction.MODERATION_ACTION.name());
		putIfNotNull(payload, "moderationActionId", moderationAction.getId());

		deliver(NotificationType.MODERATION_ACTION, moderationAction.getMember(),
			moderationTitleOf(moderationAction), MODERATION_ACTION_MESSAGE, moderationLinkOf(moderationAction),
			null, null, payload);
	}

	private String moderationTitleOf(ModerationAction moderationAction) {
		return switch (moderationAction.getTargetType()) {
			case RECORD -> MODERATION_RECORD_TITLE;
			case COMMENT -> MODERATION_COMMENT_TITLE;
			default -> MODERATION_ACTION_TITLE;
		};
	}

	/** 눌렀을 때 갈 곳 — 숨겨진 빵로그(본인에겐 보인다), 댓글이면 그 댓글이 달린 빵로그의 댓글 시트. 그 밖엔 없음. */
	private String moderationLinkOf(ModerationAction moderationAction) {
		return switch (moderationAction.getTargetType()) {
			case RECORD -> "/bbanglog/post/" + moderationAction.getTargetId();
			case COMMENT -> commentRepository.findById(moderationAction.getTargetId())
				.map(comment -> "/bbanglog/post/" + comment.getRecord().getId() + "?comments=1&commentId="
					+ comment.getId())
				.orElse(null);
			default -> null;
		};
	}

	/**
	 * 알림함에 앉히고 푸시로 내보낸다. 푸시가 실패해도 알림함에는 남는다 — 적재와 발송이
	 * 다른 트랜잭션이라 한쪽 실패가 다른 쪽을 되돌리지 않는다.
	 */
	private void deliver(NotificationType type, Member member, String title, String body,
			Store store, String storeName, Map<String, String> payload) {
		deliver(type, member, title, body, null, store, storeName, payload);
	}

	private void deliver(NotificationType type, Member member, String title, String body, String deepLink,
			Store store, String storeName, Map<String, String> payload) {
		LocalDateTime now = LocalDateTime.now();

		Notification notification = Notification.announcement(
			type, title, body, deepLink,
			store != null ? NotificationTargetType.STORE : null,
			store != null ? store.getId() : null,
			store != null ? store.getName() : storeName,
			payload);

		Notification recorded = notificationRecorder.recordFor(notification, member, now);
		eventPublisher.publishEvent(PushRequestedEvent.to(recorded.getId(), member.getId()));
	}

	private String fallbackStoreName(String fallbackStoreName) {
		return fallbackStoreName != null && !fallbackStoreName.isBlank() ? fallbackStoreName : "가게";
	}

	private void putIfNotNull(Map<String, String> payload, String key, Long value) {
		if (value != null) {
			payload.put(key, String.valueOf(value));
		}
	}

	// ── 조회 ────────────────────────────────────────────────────────────────

	/**
	 * 제보 알림만 골라 준다. 통합 알림함이 생겼어도 이 경로는 남는다 — 앱에 제보 결과만 보는
	 * 화면이 따로 있고, 그 화면이 통합 목록으로 바뀌는 것은 앱의 판단이다.
	 */
	@Transactional(readOnly = true)
	public List<ReportNotificationResponse> getMyReportNotifications(Long memberId) {
		return recipientRepository.findMineByTypes(memberId, REPORT_TYPES).stream()
			.map(ReportNotificationResponse::from)
			.toList();
	}

	private static final List<NotificationType> REPORT_TYPES = List.of(
		NotificationType.CONTRIBUTION_RESULT,
		NotificationType.ABUSE_REPORT_RESULT,
		NotificationType.MODERATION_ACTION);

	public void markAsRead(Long memberId, Long notificationId) {
		NotificationRecipient recipient = recipientRepository.findById(notificationId)
			.orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));
		if (!recipient.getMember().getId().equals(memberId)) {
			throw new AlarmException(AlarmErrorCode.ALARM_FORBIDDEN);
		}
		recipient.markAsRead(LocalDateTime.now());
	}
}
