package sweetgirl.croissant.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.code.BadgeErrorCode;
import sweetgirl.croissant.badge.dto.request.AdminBadgeCreateRequest;
import sweetgirl.croissant.badge.dto.request.AdminBadgeUpdateRequest;
import sweetgirl.croissant.badge.dto.response.AdminBadgeResponse;
import sweetgirl.croissant.badge.entity.Badge;
import sweetgirl.croissant.badge.enums.ConditionType;
import sweetgirl.croissant.badge.exception.BadgeException;
import sweetgirl.croissant.badge.repository.BadgeRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminBadgeService {

	private final BadgeRepository badgeRepository;

	@Transactional(readOnly = true)
	public List<AdminBadgeResponse> getBadges() {
		return badgeRepository.findAllByOrderBySortOrderAscIdAsc().stream()
			.map(AdminBadgeResponse::from)
			.toList();
	}

	public Long create(AdminBadgeCreateRequest request) {
		if (badgeRepository.existsByCode(request.code())) {
			throw new BadgeException(BadgeErrorCode.BADGE_CODE_CONFLICT);
		}
		validateCondition(request.conditionType(), request.threshold());
		Badge badge = Badge.create(
			request.code(),
			normalizeNullable(request.imageKey()),
			request.title().strip(),
			request.hint().strip(),
			request.conditionType(),
			request.threshold(),
			request.sortOrder());
		return badgeRepository.save(badge).getId();
	}

	public void update(Long badgeId, AdminBadgeUpdateRequest request) {
		Badge badge = getBadge(badgeId);
		ConditionType conditionType = request.conditionType() == null
			? badge.getConditionType()
			: request.conditionType();
		Integer threshold = resolveThreshold(badge, conditionType, request);
		validateCondition(conditionType, threshold);
		badge.update(
			normalizeNullable(request.imageKey()),
			normalizeNullable(request.title()),
			normalizeNullable(request.hint()),
			conditionType,
			threshold,
			request.sortOrder(),
			request.active());
	}

	public void deactivate(Long badgeId) {
		getBadge(badgeId).deactivate();
	}

	private Integer resolveThreshold(
			Badge badge,
			ConditionType conditionType,
			AdminBadgeUpdateRequest request) {
		if (conditionType == ConditionType.STARTER_ALL) {
			return null;
		}
		return request.threshold() == null ? badge.getThreshold() : request.threshold();
	}

	private void validateCondition(ConditionType conditionType, Integer threshold) {
		try {
			conditionType.target(threshold);
		} catch (IllegalArgumentException exception) {
			throw new BadgeException(BadgeErrorCode.INVALID_BADGE_CONDITION);
		}
	}

	private Badge getBadge(Long badgeId) {
		return badgeRepository.findById(badgeId)
			.orElseThrow(() -> new BadgeException(BadgeErrorCode.BADGE_NOT_FOUND));
	}

	private String normalizeNullable(String value) {
		if (value == null) {
			return null;
		}
		String stripped = value.strip();
		return stripped.isEmpty() ? null : stripped;
	}
}
