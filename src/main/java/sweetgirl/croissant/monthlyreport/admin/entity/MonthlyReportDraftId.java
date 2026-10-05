package sweetgirl.croissant.monthlyreport.admin.entity;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MonthlyReportDraftId implements Serializable {

	@Column(name = "run_id", nullable = false)
	private UUID runId;

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "year_month", nullable = false, length = 7)
	private String yearMonth;
}
