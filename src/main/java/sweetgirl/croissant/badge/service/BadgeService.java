package sweetgirl.croissant.badge.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.badge.code.BadgeErrorCode;
import sweetgirl.croissant.badge.dto.response.MemberBadgeResponse;
import sweetgirl.croissant.badge.entity.Badge;
import sweetgirl.croissant.badge.entity.MemberBadge;
import sweetgirl.croissant.badge.exception.BadgeException;
import sweetgirl.croissant.badge.repository.BadgeRepository;
import sweetgirl.croissant.badge.repository.MemberBadgeRepository;
import sweetgirl.croissant.member.service.MemberReader;

@Service
@RequiredArgsConstructor
@Transactional
public class BadgeService {

	private final MemberReader memberReader;
	private final BadgeRepository badgeRepository;
	private final MemberBadgeRepository memberBadgeRepository;
	private final MemberConditionEvaluator conditionEvaluator;

	@Value("${app.cdn-base-url:https://cdn.bbang-ggut.cloud}")
	private String cdnBaseUrl;

	public List<MemberBadgeResponse> getBadges(Long memberId) {
		memberReader.getMember(memberId);
		List<Badge> badges = badgeRepository.findAllByActiveTrueOrderBySortOrderAscIdAsc();
		if (badges.isEmpty()) {
			return List.of();
		}

		Map<Long, MemberBadge> previousAwards = awardedByBadgeId(memberId);
		List<Badge> missingBadges = badges.stream()
			.filter(badge -> !previousAwards.containsKey(badge.getId()))
			.toList();
		if (!missingBadges.isEmpty()) {
			MemberConditionSnapshot snapshot = conditionEvaluator.snapshot(memberId);
			missingBadges.stream()
				.filter(badge -> conditionEvaluator.evaluate(
					badge.getConditionType(), badge.getThreshold(), snapshot).done())
				.forEach(badge -> memberBadgeRepository.awardIfAbsent(memberId, badge.getId()));
		}

		Map<Long, MemberBadge> currentAwards = awardedByBadgeId(memberId);
		return badges.stream()
			.map(badge -> MemberBadgeResponse.of(
				badge,
				currentAwards.get(badge.getId()),
				toImageUrl(badge.getImageKey())))
			.toList();
	}

	public void celebrate(Long memberId, String code) {
		memberReader.getMember(memberId);
		badgeRepository.findByCode(code)
			.orElseThrow(() -> new BadgeException(BadgeErrorCode.BADGE_NOT_FOUND));
		MemberBadge memberBadge = memberBadgeRepository.findByMemberIdAndBadgeCode(memberId, code)
			.orElseThrow(() -> new BadgeException(BadgeErrorCode.MEMBER_BADGE_NOT_FOUND));
		memberBadge.celebrate(LocalDateTime.now());
	}

	private Map<Long, MemberBadge> awardedByBadgeId(Long memberId) {
		return memberBadgeRepository.findAllByMemberId(memberId).stream()
			.collect(Collectors.toMap(memberBadge -> memberBadge.getBadge().getId(), Function.identity()));
	}

	private String toImageUrl(String imageKey) {
		if (imageKey == null || imageKey.isBlank()) {
			return null;
		}
		if (imageKey.startsWith("http://") || imageKey.startsWith("https://")) {
			return imageKey;
		}
		return cdnBaseUrl.replaceAll("/+$", "") + "/" + imageKey.replaceAll("^/+", "");
	}
}
