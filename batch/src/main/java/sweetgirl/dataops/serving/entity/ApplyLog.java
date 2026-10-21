package sweetgirl.dataops.serving.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import sweetgirl.dataops.serving.enums.ApplyOrigin;

/**
 * 운영 DB 쓰기 감사 로그 — 모든 운영 쓰기는 예외 없이 여기 선(先)기록 후 실행한다 (불변, 전이 없음).
 */
@Entity
@Table(name = "apply_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplyLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	@Column(nullable = false, length = 60)
	private String target;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "old_value")
	private String oldValue;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "new_value")
	private String newValue;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ApplyOrigin origin;

	@Column(name = "proposal_id")
	private Long proposalId;

	@Column(name = "snapshot_id")
	private Long snapshotId;

	@Column(name = "applied_at", nullable = false)
	private LocalDateTime appliedAt;

	@Builder
	public ApplyLog(Long storeId, String target, String oldValue, String newValue,
			ApplyOrigin origin, Long proposalId, Long snapshotId) {
		this.storeId = storeId;
		this.target = target;
		this.oldValue = oldValue;
		this.newValue = newValue;
		this.origin = origin;
		this.proposalId = proposalId;
		this.snapshotId = snapshotId;
		this.appliedAt = LocalDateTime.now();
	}
}
