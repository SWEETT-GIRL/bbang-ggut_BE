package sweetgirl.croissant.monthlyreport.service;

import java.util.UUID;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MonthlyReportFailureLogger {
	private MonthlyReportFailureLogger() {
		// 정적 진단 로그만 제공한다.
	}

	public static void logFailure(UUID runId, Long memberId, Long notificationId, String stage,
			RuntimeException exception) {
		Throwable cause = exception;
		for (int depth = 0; depth < 20 && cause.getCause() != null && cause.getCause() != cause; depth++) {
			cause = cause.getCause();
		}
		String origin = cause.getStackTrace().length == 0 ? "unknown" : cause.getStackTrace()[0].toString();
		log.warn("월간 보고서 처리 실패 runId={} memberId={} notificationId={} stage={} exceptionType={} causeType={} origin={}",
			runId, memberId, notificationId, stage, exception.getClass().getName(), cause.getClass().getName(), origin);
	}
}
