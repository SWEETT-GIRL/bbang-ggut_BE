package sweetgirl.croissant.mission.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.service.MemberConditionEvaluator;
import sweetgirl.croissant.badge.service.MemberConditionSnapshot;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.mission.dto.response.MemberMissionResponse;
import sweetgirl.croissant.mission.entity.MemberMission;
import sweetgirl.croissant.mission.repository.MemberMissionRepository;
import sweetgirl.croissant.mission.repository.MissionRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class MemberMissionService {

	private final MemberReader memberReader;
	private final MissionRepository missionRepository;
	private final MemberMissionRepository memberMissionRepository;
	private final MemberConditionEvaluator conditionEvaluator;

	/**
	 * 조회가 곧 판정이다(뱃지와 같은 lazy 방식). 다만 판정은 아직 못 끝낸 미션에만 한다 —
	 * 이미 끝낸 미션을 다시 재보면, 근거를 지웠을 때 해낸 일이 없던 일이 된다.
	 */
	public List<MemberMissionResponse> getMissions(Long memberId) {
		memberReader.getMember(memberId);
		var missions = missionRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
		if (missions.isEmpty()) {
			return List.of();
		}

		Map<Long, MemberMission> previous = completedByMissionId(memberId);
		var pending = missions.stream()
			.filter(mission -> !previous.containsKey(mission.getId()))
			.toList();

		MemberConditionSnapshot snapshot = pending.isEmpty() ? null : conditionEvaluator.snapshot(memberId);
		if (snapshot != null) {
			pending.stream()
				.filter(mission -> conditionEvaluator.evaluate(
					mission.getConditionType(), mission.getThreshold(), snapshot).done())
				.forEach(mission -> memberMissionRepository.completeIfAbsent(memberId, mission.getId()));
		}

		Map<Long, MemberMission> completed = completedByMissionId(memberId);
		// 아직 못 끝낸 미션은 얼마나 왔는지 보여줘야 하므로 진행률은 그대로 현재 상태에서 읽는다.
		MemberConditionSnapshot progressSnapshot = snapshot != null ? snapshot : conditionEvaluator.snapshot(memberId);
		return missions.stream()
			.map(mission -> MemberMissionResponse.of(
				mission,
				conditionEvaluator.evaluate(mission.getConditionType(), mission.getThreshold(), progressSnapshot),
				completed.containsKey(mission.getId())))
			.toList();
	}

	private Map<Long, MemberMission> completedByMissionId(Long memberId) {
		return memberMissionRepository.findAllByMemberId(memberId).stream()
			.collect(Collectors.toMap(it -> it.getMission().getId(), Function.identity()));
	}
}
