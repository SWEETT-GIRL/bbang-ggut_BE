package sweetgirl.croissant.monthlyreport.entity;

import java.io.Serializable;

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
public class MonthlyReportMemberId implements Serializable {

	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "year_month", nullable = false, length = 7)
	private String yearMonth;
}
