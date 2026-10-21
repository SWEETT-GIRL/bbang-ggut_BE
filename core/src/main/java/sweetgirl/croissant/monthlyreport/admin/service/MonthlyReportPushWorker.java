package sweetgirl.croissant.monthlyreport.admin.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.PushDispatcher;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportFailureLogger;

@Service
@RequiredArgsConstructor
public class MonthlyReportPushWorker {
	private final MonthlyReportPushCommandService pushCommandService;
	private final PushDispatcher dispatcher;

	/** 예약 선점과 결과 저장 사이에는 DB 트랜잭션이나 행 잠금을 유지하지 않는다. */
	@Transactional(propagation = Propagation.NEVER)
	public void sendScheduledReportPush(Long notificationId) {
		pushCommandService.claimScheduledPush(notificationId).ifPresent(claim -> {
			PushDispatcher.Result result;
			try {
				result = dispatcher.dispatchWithResult(claim.notification(), claim.member(),
					LocalDateTime.now(ZoneId.of("Asia/Seoul")));
			} catch (RuntimeException exception) {
				MonthlyReportFailureLogger.logFailure(null, claim.member().getId(), notificationId, "dispatch",
					exception);
				result = PushDispatcher.Result.FAILED;
			}
			pushCommandService.completeScheduledPush(notificationId, claim.token(), result);
		});
	}
}
