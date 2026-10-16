package sweetgirl.croissant.admin.dto.response;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;

import sweetgirl.croissant.admin.entity.AdminAuditLog;

public record AdminAuditLogResponse(
		Long id,
		String adminLabel,
		String method,
		String path,
		String query,
		String targetType,
		String targetId,
		String requestSummary,
		Integer statusCode,
		String ip,
		String userAgent,
		String requestId,
		Instant createdAt) {

	public static AdminAuditLogResponse from(AdminAuditLog auditLog) {
		return new AdminAuditLogResponse(
			auditLog.getId(),
			auditLog.getAdminLabel(),
			auditLog.getMethod(),
			auditLog.getPath(),
			auditLog.getQuery(),
			auditLog.getTargetType(),
			auditLog.getTargetId(),
			auditLog.getRequestSummary(),
			auditLog.getStatusCode(),
			auditLog.getIp(),
			auditLog.getUserAgent(),
			auditLog.getRequestId(),
			auditLog.getCreatedAt());
	}

	public record PageResponse(
			List<AdminAuditLogResponse> content,
			int page,
			int size,
			long totalElements,
			boolean hasNext) {

		public static PageResponse from(Page<AdminAuditLog> page) {
			return new PageResponse(
				page.getContent().stream().map(AdminAuditLogResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.hasNext());
		}
	}
}
