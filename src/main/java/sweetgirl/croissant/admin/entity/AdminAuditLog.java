package sweetgirl.croissant.admin.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 어드민 API 한 번의 쓰기(와 회원 개인 정보 조회) 기록. 고치지 않고 쌓기만 한다. */
@Entity
@Table(name = "admin_audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminAuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "admin_label", length = 50)
	private String adminLabel;

	@Column(nullable = false, length = 10)
	private String method;

	@Column(nullable = false, length = 255)
	private String path;

	@Column(length = 500)
	private String query;

	@Column(name = "target_type", length = 50)
	private String targetType;

	@Column(name = "target_id", length = 64)
	private String targetId;

	@Column(name = "request_summary", columnDefinition = "TEXT")
	private String requestSummary;

	@Column(name = "status_code")
	private Integer statusCode;

	@Column(length = 45)
	private String ip;

	@Column(name = "user_agent", length = 255)
	private String userAgent;

	@Column(name = "request_id", length = 64)
	private String requestId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Builder
	public AdminAuditLog(String adminLabel, String method, String path, String query, String targetType,
			String targetId, String requestSummary, Integer statusCode, String ip, String userAgent, String requestId,
			Instant createdAt) {
		this.adminLabel = adminLabel;
		this.method = method;
		this.path = path;
		this.query = query;
		this.targetType = targetType;
		this.targetId = targetId;
		this.requestSummary = requestSummary;
		this.statusCode = statusCode;
		this.ip = ip;
		this.userAgent = userAgent;
		this.requestId = requestId;
		this.createdAt = createdAt;
	}
}
