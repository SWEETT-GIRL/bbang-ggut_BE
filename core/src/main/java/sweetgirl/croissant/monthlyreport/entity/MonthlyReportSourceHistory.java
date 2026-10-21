package sweetgirl.croissant.monthlyreport.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportSourceType;

/** 원본 테이블의 트리거가 기록한다. 과거 버전은 수정하지 않는다. */
@Entity
@Table(name = "monthly_report_source_history")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MonthlyReportSourceHistory {

	@Id
	private Long id;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private MonthlyReportSourceType sourceType;
	@Column(nullable = false, length = 100)
	private String sourceKey;
	private Long memberId;
	private Long parentId;
	@Column(nullable = false)
	private LocalDateTime recordedAt;
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String payload;
}
