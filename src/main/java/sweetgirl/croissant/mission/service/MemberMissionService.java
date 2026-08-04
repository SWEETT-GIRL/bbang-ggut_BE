package sweetgirl.croissant.mission.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.service.MemberConditionEvaluator;
import sweetgirl.croissant.badge.service.MemberConditionSnapshot;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.mission.dto.response.MemberMissionResponse;
import sweetgirl.croissant.mission.repository.MissionRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberMissionService {

	private final MemberReader memberReader;
	private final MissionRepository missionRepository;
	private final MemberConditionEvaluator conditionEvaluator;

	public List<MemberMissionResponse> getMissions(Long memberId) {
		memberReader.getMember(memberId);
		var missions = missionRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
		if (missions.isEmpty()) {
			return List.of();
		}
		MemberConditionSnapshot snapshot = conditionEvaluator.snapshot(memberId);
		return missions.stream()
			.map(mission -> MemberMissionResponse.of(
				mission,
				conditionEvaluator.evaluate(mission.getConditionType(), mission.getThreshold(), snapshot)))
			.toList();
	}
}
