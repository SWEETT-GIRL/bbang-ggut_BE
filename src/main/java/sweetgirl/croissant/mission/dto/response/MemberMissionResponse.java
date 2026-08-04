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

	public static MemberMissionResponse of(Mission mission, ConditionProgress progress) {
		return new MemberMissionResponse(
			mission.getCode(),
			mission.getTitle(),
			mission.getActionDeeplink(),
			new MissionProgressResponse(progress.current(), progress.target()),
			progress.done(),
			mission.getBadgeCode());
	}
}
