package sweetgirl.croissant.mission.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import sweetgirl.croissant.member.entity.Member;

/**
 * 미션을 끝낸 기록.
 *
 * 미션 완료를 조회 시점의 상태로만 계산하면, 근거를 지웠을 때 해낸 일이 없던 일이 된다.
 * 완료는 사건이므로 사건이 일어난 시각을 남긴다 — 뱃지(member_badges)와 같은 규칙이다.
 */
@Entity
@Table(name = "member_missions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberMission {

	@EmbeddedId
	private MemberMissionId id;

	@MapsId("memberId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	@MapsId("missionId")
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "mission_id", nullable = false)
	private Mission mission;

	@Column(nullable = false)
	private LocalDateTime completedAt;

	private MemberMission(Member member, Mission mission, LocalDateTime completedAt) {
		this.id = new MemberMissionId(member.getId(), mission.getId());
		this.member = member;
		this.mission = mission;
		this.completedAt = completedAt;
	}

	public static MemberMission completed(Member member, Mission mission, LocalDateTime completedAt) {
		return new MemberMission(member, mission, completedAt);
	}
}
