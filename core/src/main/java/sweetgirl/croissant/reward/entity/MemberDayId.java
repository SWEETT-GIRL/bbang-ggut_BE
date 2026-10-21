package sweetgirl.croissant.reward.entity;

import java.io.Serializable;
import java.time.LocalDate;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 하루에 한 줄인 표(출석 · 밥)의 키 — 회원과 한국 날짜. */
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class MemberDayId implements Serializable {

	private Long memberId;
	private LocalDate day;
}
