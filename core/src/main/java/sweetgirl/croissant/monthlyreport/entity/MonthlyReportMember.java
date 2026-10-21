package sweetgirl.croissant.monthlyreport.entity;

import java.time.Instant;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;

/** 발행본은 불변이다. 발행은 저장소의 조건부 upsert로만 수행하고 현재 회원 상태와 조인하지 않는다. */
@Entity
@Table(name = "monthly_report_member")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportMember {

	@EmbeddedId
	private MonthlyReportMemberId id;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private MonthlyReportStatus status;

	/** 발행에 사용한 캐릭터 값. 현재 선택이나 카탈로그 이름 변경을 따라가지 않는다. */
	@Column(name = "character_code", length = 100)
	private String characterCode;

	@Column(name = "character_name", length = 100)
	private String characterName;

	@Column(name = "character_image_key", length = 2048)
	private String characterImageKey;

	/** 발행에 사용한 행성 값. 현재 진행도와 독립적인 스냅샷이다. */
	@Column(name = "planet_code", length = 100)
	private String planetCode;

	@Column(name = "planet_name", length = 100)
	private String planetName;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String payload;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "raw_metrics", columnDefinition = "jsonb")
	private String rawMetrics;

	@Column(name = "calculation_version", length = 100)
	private String calculationVersion;

	@Column(name = "generated_at")
	private Instant generatedAt;

	@Column(name = "published_at")
	private Instant publishedAt;
}
