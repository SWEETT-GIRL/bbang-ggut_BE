package sweetgirl.croissant.pat.entity;

import java.io.Serializable;
import java.time.LocalDate;

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
public class MemberPatId implements Serializable {

	@Column(name = "from_member_id")
	private Long fromMemberId;

	@Column(name = "to_member_id")
	private Long toMemberId;

	/** 서버가 KST 로 계산한 날짜 — 사람마다 하루 1번의 "하루". */
	@Column(name = "kst_date")
	private LocalDate kstDate;
}
