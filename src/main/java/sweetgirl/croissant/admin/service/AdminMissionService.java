package sweetgirl.croissant.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.repository.BadgeRepository;
import sweetgirl.croissant.mission.code.MissionErrorCode;
import sweetgirl.croissant.mission.dto.request.AdminMissionCreateRequest;
import sweetgirl.croissant.mission.dto.request.AdminMissionUpdateRequest;
import sweetgirl.croissant.mission.dto.response.AdminMissionResponse;
import sweetgirl.croissant.mission.entity.Mission;
import sweetgirl.croissant.mission.exception.MissionException;
import sweetgirl.croissant.mission.repository.MissionRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminMissionService {

	private final MissionRepository missionRepository;
	private final BadgeRepository badgeRepository;

	@Transactional(readOnly = true)
	public List<AdminMissionResponse> getMissions() {
		return missionRepository.findAllByOrderBySortOrderAscIdAsc().stream()
			.map(AdminMissionResponse::from)
			.toList();
	}

	public Long create(AdminMissionCreateRequest request) {
		if (missionRepository.existsByCode(request.code())) {
			throw new MissionException(MissionErrorCode.MISSION_CODE_CONFLICT);
		}
		validateCondition(request.conditionType(), request.threshold());
		validateBadgeCode(request.badgeCode());
		Mission mission = Mission.create(
			request.code(),
			request.title().strip(),
			request.conditionType(),
			request.threshold(),
			request.actionDeeplink().strip(),
			normalizeNullable(request.badgeCode()),
			request.sortOrder());
		return missionRepository.save(mission).getId();
	}

	public void update(Long missionId, AdminMissionUpdateRequest request) {
		Mission mission = getMission(missionId);
		ConditionType conditionType = request.conditionType() == null
			? mission.getConditionType()
			: request.conditionType();
		Integer threshold = resolveThreshold(mission, conditionType, request);
		String badgeCode = request.badgeCode() == null ? mission.getBadgeCode()
			: normalizeNullable(request.badgeCode());
		validateCondition(conditionType, threshold);
		validateBadgeCode(badgeCode);
		mission.update(
			normalizeNullable(request.title()),
			conditionType,
			threshold,
			normalizeNullable(request.actionDeeplink()),
			badgeCode,
			request.sortOrder(),
			request.active());
	}

	public void deactivate(Long missionId) {
		getMission(missionId).deactivate();
	}

	private Integer resolveThreshold(
			Mission mission,
			ConditionType conditionType,
			AdminMissionUpdateRequest request) {
		if (conditionType == ConditionType.STARTER_ALL) {
			return null;
		}
		return request.threshold() == null ? mission.getThreshold() : request.threshold();
	}

	private void validateCondition(ConditionType conditionType, Integer threshold) {
		try {
			conditionType.target(threshold);
		} catch (IllegalArgumentException exception) {
			throw new MissionException(MissionErrorCode.INVALID_MISSION_CONDITION);
		}
	}

	private void validateBadgeCode(String badgeCode) {
		String normalized = normalizeNullable(badgeCode);
		if (normalized != null && !badgeRepository.existsByCode(normalized)) {
			throw new MissionException(MissionErrorCode.BADGE_NOT_FOUND);
		}
	}

	private Mission getMission(Long missionId) {
		return missionRepository.findById(missionId)
			.orElseThrow(() -> new MissionException(MissionErrorCode.MISSION_NOT_FOUND));
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}
}
