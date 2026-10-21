package sweetgirl.croissant.reward.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 밥을 준 하루. 읽기 전용이다 — 행은 {@code MemberFeedDayRepository.insertIfAbsent} 로만 생긴다. */
@Entity
@Table(name = "member_feed_days")
@IdClass(MemberDayId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberFeedDay {

	@Id
	@Column(name = "member_id")
	private Long memberId;

	@Id
	@Column(name = "fed_on")
	private LocalDate day;

	/** 룰렛이 멈춘 칸(0 부터, 시계 방향). */
	@JdbcTypeCode(SqlTypes.SMALLINT)
	@Column(nullable = false)
	private int wedge;

	/** 실제로 받은 양. */
	@Column(nullable = false)
	private int crumbs;

	/** 오늘 광고를 보고 더 돌릴 수 있는 남은 횟수. 행이 생길 때 하루 상한으로 시작해 광고 한 편마다 하나씩 준다. */
	@Column(name = "feed_extra_left", nullable = false)
	private int extraLeft;

	/** 광고를 끝까지 봐서 한 판이 열려 있다. 돌리면 다시 false 다. */
	@Column(name = "feed_extra_ready", nullable = false)
	private boolean extraReady;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;
}
