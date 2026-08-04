package sweetgirl.croissant.mission.dto.response;

import sweetgirl.croissant.badge.service.ConditionProgress;
import sweetgirl.croissant.mission.entity.Mission;

public record MemberMissionResponse(
		String code,
		String title,
		String deeplink,
		MissionProgressResponse progress,
		boolean done,
		String badgeCode) {

	/**
	 * completed는 "예전에 끝냈다"는 기록이다. 근거 데이터가 줄어 지금 조건을 못 채워도
	 * 끝낸 미션은 끝난 채로 남고, 진행률도 다 찬 것으로 보여준다 — 완료한 칸이
	 * 도로 줄어드는 화면은 해낸 일을 부정한다.
	 */
	public static MemberMissionResponse of(Mission mission, ConditionProgress progress, boolean completed) {
		boolean done = completed || progress.done();
		long current = done ? Math.max(progress.current(), progress.target()) : progress.current();
		return new MemberMissionResponse(
			mission.getCode(),
			mission.getTitle(),
			mission.getActionDeeplink(),
			new MissionProgressResponse(current, progress.target()),
			done,
			mission.getBadgeCode());
	}
}
