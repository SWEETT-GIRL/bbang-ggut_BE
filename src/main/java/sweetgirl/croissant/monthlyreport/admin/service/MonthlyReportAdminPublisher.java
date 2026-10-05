package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.entity.Notification;
import sweetgirl.croissant.alarm.enums.NotificationChannel;
import sweetgirl.croissant.alarm.enums.NotificationType;
import sweetgirl.croissant.alarm.service.NotificationRecorder;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportPush;
import sweetgirl.croissant.monthlyreport.admin.repository.MonthlyReportPushRepository;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPublicationService;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportTargetReader;

@Service
@RequiredArgsConstructor
public class MonthlyReportAdminPublisher {
	private final MonthlyReportPublicationService publication;
	private final MonthlyReportPayloadCodec codec;
	private final ObjectMapper mapper;
	private final MemberReader members;
	private final NotificationRecorder notifications;
	private final MonthlyReportPushRepository pushes;
	private final MonthlyReportTargetReader targets;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean publish(MonthlyReportDraft draft, Instant pushAt) {
		Long memberId = draft.getId().getMemberId();
		String month = draft.getId().getYearMonth();
		if (!targets.isEligible(memberId, month)) {
			return false;
		}
		try {
			boolean created = publication.publishWithoutNotification(memberId, month,
				codec.read(draft.getPayload(), memberId, month), mapper.readTree(draft.getRawMetrics()),
				"admin-bread-power-v1", draft.getGeneratedAt());
			if (created) {
				Member member = members.getMember(memberId);
				String label = Integer.parseInt(month.substring(5)) + "월";
				Notification notification = notifications.recordFor(Notification.announcement(NotificationType.NOTICE,
					pushAt == null ? NotificationChannel.IN_APP : NotificationChannel.PUSH,
					label + " 빵일지가 도착했어요", label + "의 빵 기록과 활동을 확인해 보세요.",
					"/reports/monthly/" + month), member, LocalDateTime.now(ZoneId.of("Asia/Seoul")));
				if (pushAt != null) {
					pushes.save(MonthlyReportPush.schedule(notification.getId(), memberId, pushAt));
				}
			}
			return created;
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Invalid prepared report", e);
		}
	}
}
