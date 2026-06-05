package sweetgirl.croissant.alarm.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.code.AlarmErrorCode;
import sweetgirl.croissant.alarm.dto.response.ReportNotificationResponse;
import sweetgirl.croissant.alarm.entity.FcmToken;
import sweetgirl.croissant.alarm.entity.ReportNotification;
import sweetgirl.croissant.alarm.enums.ReportKind;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.exception.AlarmException;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository;
import sweetgirl.croissant.alarm.repository.ReportNotificationRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

@Service
@RequiredArgsConstructor
@Transactional
public class ReportNotificationService {

	private static final Logger log = LoggerFactory.getLogger(ReportNotificationService.class);

	private final ReportNotificationRepository reportNotificationRepository;
	private final FcmTokenRepository fcmTokenRepository;
	private final FcmService fcmService;

	// 제보 승인/거절 결과를 사용자에게 알림 — DB 히스토리 저장 + FCM 발송
	// member 가 null 인 경우(익명 메뉴 추가 제보 등) 알림을 보낼 대상이 없으므로 silent skip
	public void notifyReportResult(Member member, Store store, Long reportId, ReportKind reportKind,
			ReportNotificationAction action, String subjectLabel) {
		if (member == null) {
			log.info("notifyReportResult: 제보자 없음 [reportId={}, kind={}]", reportId, reportKind);
			return;
		}

		String title = action == ReportNotificationAction.APPROVED
			? "제보가 반영되었어요"
			: "제보가 반려되었어요";
		String storeName = store != null ? store.getName() : "가게";
		String body = action == ReportNotificationAction.APPROVED
			? String.format("%s의 %s 제보가 승인되었어요 🎉", storeName, subjectLabel)
			: String.format("%s의 %s 제보가 반려되었어요", storeName, subjectLabel);

		reportNotificationRepository.save(ReportNotification.builder()
			.member(member)
			.store(store)
			.reportId(reportId)
			.reportKind(reportKind)
			.action(action)
			.title(title)
			.message(body)
			.build());

		List<FcmToken> tokens = fcmTokenRepository.findByMemberAndIsActive(member, true);
		if (tokens.isEmpty()) {
			log.info("notifyReportResult: FCM 토큰 없음 [memberId={}, reportId={}]", member.getId(), reportId);
			return;
		}

		Map<String, String> data = new HashMap<>();
		data.put("type", "REPORT_RESULT");
		data.put("reportId", String.valueOf(reportId));
		data.put("reportKind", reportKind.name());
		data.put("action", action.name());
		if (store != null) {
			data.put("storeId", String.valueOf(store.getId()));
		}

		// 같은 디바이스 다중 row(legacy 데이터)에 의한 중복 발송 방지
		List<String> uniqueTokens = tokens.stream()
			.map(FcmToken::getToken)
			.distinct()
			.toList();

		for (String token : uniqueTokens) {
			try {
				fcmService.sendPushNotification(token, title, body, data);
			} catch (AlarmException e) {
				log.warn("FCM 발송 실패 [memberId={}, reportId={}, token={}]",
					member.getId(), reportId, token);
			}
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
