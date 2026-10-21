package sweetgirl.croissant.dev.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.global.logging.entity.RequestLog;

public record DevRequestLogResponse(
		LocalDateTime createdAt,
		String method,
		String endpoint,
		String queryParams,
		Integer status,
		Integer durationMs,
		String clientIp,
		String userAgent,
		String errorCode,
		String errorMessage) {

	public static DevRequestLogResponse from(RequestLog log) {
		return new DevRequestLogResponse(
			log.getCreatedAt(),
			log.getHttpMethod(),
			log.getEndpoint(),
			log.getQueryParams(),
			log.getResponseStatus(),
			log.getResponseTimeMs(),
			log.getIpAddress(),
			log.getUserAgent(),
			log.getErrorCode(),
			log.getErrorMessage());
	}
}
