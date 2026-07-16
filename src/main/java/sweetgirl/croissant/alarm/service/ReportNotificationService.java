package sweetgirl.croissant.alarm.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.entity.ModerationAction;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.response.ReportNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.ReportNotification;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.ReportNotificationRepository;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@Service
@RequiredArgsConstructor
@Transactional
public class ReportNotificationService {

	private static final Logger log = LoggerFactory.getLogger(ReportNotificationService.class);
	private static final String ABUSE_REPORT_RESULT_TITLE = "신고 검토가 완료되었습니다.";
	private static final String ABUSE_REPORT_RESULT_MESSAGE = "운영 정책에 따라 필요한 조치를 진행했습니다.";
	private static final String MODERATION_ACTION_TITLE = "운영 정책 위반으로 콘텐츠가 제한되었습니다.";
	private static final String MODERATION_ACTION_MESSAGE = "결정에 동의하지 않는 경우 30일 이내에 이의제기를 요청할 수 있습니다.";

	private final ReportNotificationRepository reportNotificationRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final FcmService fcmService;

	// 제보 승인/거절 결과를 사용자에게 알림 — DB 히스토리 저장 + FCM 발송
	// member 가 null 인 경우(익명 메뉴 추가 제보 등) 알림을 보낼 대상이 없으므로 silent skip
	public void notifyContributionReportResult(Member member, Store store, String fallbackStoreName, Long reportId,
			Long contributionReportId, ReportKind reportKind, ReportNotificationAction action, String subjectLabel) {
		if (member == null) {
			log.info("notifyReportResult: 제보자 없음 [reportId={}, kind={}]", reportId, reportKind);
			return;
		}

		String title = action == ReportNotificationAction.APPROVED
			? "제보가 반영되었어요"
			: "제보가 반려되었어요";
		String storeName = store != null ? store.getName() : fallbackStoreName(fallbackStoreName);
		String body = action == ReportNotificationAction.APPROVED
			? String.format("%s의 %s 제보가 승인되었어요 🎉", storeName, subjectLabel)
			: String.format("%s의 %s 제보가 반려되었어요", storeName, subjectLabel);

		Map<String, String> data = new HashMap<>();
		data.put("type", "REPORT_RESULT");
		data.put("reportId", String.valueOf(reportId));
		data.put("reportKind", reportKind.name());
		putIfNotNull(data, "contributionReportId", contributionReportId);
		data.put("action", action.name());
		if (store != null) {
			data.put("storeId", String.valueOf(store.getId()));
		}

		saveAndSend(
			ReportNotification.ofContributionReportResult(member, store, reportId, contributionReportId, reportKind,
				action, title, body),
			data);
	}

	private String fallbackStoreName(String fallbackStoreName) {
		return fallbackStoreName != null && !fallbackStoreName.isBlank() ? fallbackStoreName : "가게";
	}

	public void notifyAbuseReportResult(AbuseReport report) {
		if (report == null || report.getReporter() == null) {
			log.info("notifyAbuseReportResult: 신고자 없음");
			return;
		}

		Map<String, String> data = new HashMap<>();
		data.put("type", ReportNotificationAction.ABUSE_REPORT_RESULT.name());
		data.put("reportId", String.valueOf(report.getId()));
		data.put("abuseReportId", String.valueOf(report.getId()));
		data.put("reportKind", ReportKind.ABUSE_REPORT.name());

		saveAndSend(
			ReportNotification.ofAbuseReportResult(report, ABUSE_REPORT_RESULT_TITLE, ABUSE_REPORT_RESULT_MESSAGE),
			data);
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
		Map<String, String> data = new HashMap<>();
		data.put("type", ReportNotificationAction.MODERATION_ACTION.name());
		data.put("reportId", String.valueOf(reportId));
		data.put("abuseReportId", String.valueOf(reportId));
		data.put("reportKind", ReportKind.ABUSE_REPORT.name());
		putIfNotNull(data, "moderationActionId", moderationAction.getId());

		saveAndSend(
			ReportNotification.ofModerationAction(moderationAction, MODERATION_ACTION_TITLE, MODERATION_ACTION_MESSAGE),
			data);
	}

	private void saveAndSend(ReportNotification notification, Map<String, String> data) {
		reportNotificationRepository.save(notification);

		Member member = notification.getMember();
		List<FcmToken> tokens = fcmTokenRepository.findByMemberAndIsActive(member, true);
		if (tokens.isEmpty()) {
			log.info("ReportNotification: FCM 토큰 없음 [memberId={}, reportId={}, action={}]",
				member.getId(), notification.getReportId(), notification.getAction());
			return;
		}

		// 같은 디바이스 다중 row(legacy 데이터)에 의한 중복 발송 방지
		List<String> uniqueTokens = tokens.stream()
			.map(FcmToken::getToken)
			.distinct()
			.toList();

		for (String token : uniqueTokens) {
			try {
				fcmService.sendPushNotification(token, notification.getTitle(), notification.getMessage(), data);
			} catch (AlarmException e) {
				log.warn("FCM 발송 실패 [memberId={}, reportId={}, token={}]",
					member.getId(), notification.getReportId(), maskToken(token));
			}
		}
	}

	private String maskToken(String token) {
		if (token == null || token.length() <= 12) {
			return "***";
		}
		return token.substring(0, 6) + "..." + token.substring(token.length() - 6);
	}

	private void putIfNotNull(Map<String, String> data, String key, Long value) {
		if (value != null) {
			data.put(key, String.valueOf(value));
		}
	}

	@Transactional(readOnly = true)
	public List<ReportNotificationResponse> getMyReportNotifications(Long memberId) {
		return reportNotificationRepository.findByMemberIdOrderBySentAtDesc(memberId).stream()
			.map(ReportNotificationResponse::from)
			.toList();
	}

	public void markAsRead(Long memberId, Long notificationId) {
		ReportNotification notification = reportNotificationRepository.findById(notificationId)
			.orElseThrow(() -> new AlarmException(AlarmErrorCode.ALARM_NOT_FOUND));
		if (!notification.getMember().getId().equals(memberId)) {
			throw new AlarmException(AlarmErrorCode.ALARM_FORBIDDEN);
		}
		notification.markAsRead();
	}
}
