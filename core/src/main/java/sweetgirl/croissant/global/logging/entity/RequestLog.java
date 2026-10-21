package sweetgirl.croissant.global.logging.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.global.logging.LogEventType;

@Entity
@Table(name = "request_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class RequestLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column
	private Long memberId;

	@Column(nullable = false, length = 10)
	private String httpMethod;

	@Column(nullable = false, length = 500)
	private String endpoint;

	@Column(columnDefinition = "TEXT")
	private String queryParams;

	@Column(columnDefinition = "TEXT")
	private String requestBody;

	@Column(nullable = false)
	private Integer responseStatus;

	@Column
	private Integer responseTimeMs;

	@Column(length = 45)
	private String ipAddress;

	@Column(columnDefinition = "TEXT")
	private String userAgent;

	@Column
	private Long storeId;

	@Enumerated(EnumType.STRING)
	@Column(length = 50)
	private LogEventType eventType;

	@Column(length = 50)
	private String errorCode;

	@Column(columnDefinition = "TEXT")
	private String errorMessage;

	/** 앱 세션 id (요청 헤더 X-Session-Id). 헤더 배포 전 앱은 NULL. 추천 칩의 "세션에서 본 상권"(#661)이 쓴다 */
	@Column(length = 64)
	private String sessionId;

	@CreatedDate
	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Builder
	public RequestLog(Long memberId, String httpMethod, String endpoint, String queryParams,
			String requestBody, Integer responseStatus, Integer responseTimeMs,
			String ipAddress, String userAgent, Long storeId, LogEventType eventType,
			String errorCode, String errorMessage, String sessionId) {
		this.memberId = memberId;
		this.httpMethod = httpMethod;
		this.endpoint = endpoint;
		this.queryParams = queryParams;
		this.requestBody = requestBody;
		this.responseStatus = responseStatus;
		this.responseTimeMs = responseTimeMs;
		this.ipAddress = ipAddress;
		this.userAgent = userAgent;
		this.storeId = storeId;
		this.eventType = eventType;
		this.errorCode = errorCode;
		this.errorMessage = errorMessage;
		this.sessionId = sessionId;
	}
}
