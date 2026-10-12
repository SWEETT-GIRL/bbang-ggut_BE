package sweetgirl.croissant.reward.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseProgress;
import sweetgirl.croissant.course.enums.CourseProgressStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.course.service.CourseAccessPolicy;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

/**
 * 빵투어 보상 — 가게 다녀옴, 완주, 사진 완주. 내가 만든 것 · 일행인 것 · 담은(남이 만든) 것 모두 각자의 체크로 받는다.
 *
 * <p>빵투어는 새로 만들기가 쉬워서 반복해 모으는 길을 하나씩 막아 둔다.
 * <ul>
 *   <li>다녀옴 — 같은 빵투어의 같은 가게는 평생 한 번, 같은 가게는 하루 한 번, 하루 8 곳까지, 빵투어 날짜 안에서만.</li>
 *   <li>완주 · 사진 완주 — 각각 한 주(월~일)에 빵투어 하나만. 그 주에 이미 받았으면 그 빵투어는 보상 없이 끝난다
 *       (다음 주에 뒤늦게 주지 않는다).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class TourRewardService {

	static final String STEP_COMPLETE = "COMPLETE";
	static final String STEP_PHOTO_COMPLETE = "PHOTO_COMPLETE";

	private final CrumbGrantService crumbGrantService;
	private final RewardSettings rewardSettings;
	private final CrumbLedgerRepository ledgerRepository;
	private final CourseProgressRepository courseProgressRepository;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final CourseAccessPolicy courseAccessPolicy;

	/**
	 * 가게를 다녀왔다고 표시했을 때의 +3.
	 *
	 * @param progress 이 회원의 이 빵투어 진행. 처음 표시한 날이 이미 찍혀 있어야 한다
	 */
	public List<RewardItemResponse> rewardVisit(
			Member member,
			Course course,
			Long storeId,
			CourseProgress progress,
			LocalDate today) {
		if (member.isGuest() || !isWithinTourDates(member.getId(), course, progress, today)) {
			return List.of();
		}
		Long memberId = member.getId();
		// 같은 가게를 오늘 이미 받았는지와 하루 8 곳을 세는 일이 지급과 한 줄에 서야 한다
		crumbGrantService.serialize(memberId);
		if (ledgerRepository.existsByMemberIdAndReasonAndRefTypeAndRefIdAndEarnedOn(
			memberId, CrumbReason.TOUR_VISIT, RewardRefType.STORE, storeId, today)) {
			return List.of();
		}
		GrantResult result = crumbGrantService.grantWithDailyCap(
			memberId, CrumbReason.TOUR_VISIT, rewardSettings.get(RewardSettingKey.TOUR_VISIT),
			"tour_visit:" + course.getId() + ":" + storeId, RewardRefType.STORE, storeId,
			rewardSettings.get(RewardSettingKey.TOUR_VISIT_DAILY_CAP), today);
		return result.isGranted() || result.isCapped()
			? List.of(RewardItemResponse.crumbs(result, CrumbReason.TOUR_VISIT))
			: List.of();
	}

	/**
	 * 방금 완주한 빵투어들의 보상. 진행이 "진행 중 → 완주"로 바뀐 그 순간에만 불린다 — 완주는 되돌아가지 않으므로
	 * 한 빵투어에 한 번이다.
	 */
	public List<RewardItemResponse> rewardCompletions(Member member, List<Long> finishedCourseIds, LocalDate today) {
		if (member.isGuest() || finishedCourseIds.isEmpty()) {
			return List.of();
		}
		List<RewardItemResponse> rewards = new ArrayList<>();
		for (Long courseId : finishedCourseIds) {
			rewards.add(RewardItemResponse.tourStep(courseId, STEP_COMPLETE));
			rewards.add(grantOncePerWeek(member.getId(), CrumbReason.TOUR_COMPLETE,
				rewardSettings.get(RewardSettingKey.TOUR_COMPLETE), "tour_complete:" + courseId, courseId, today));
		}
		return rewards;
	}

	/**
	 * 완주한 빵투어 가운데 모든 가게에 사진 붙은 기록이 달린 것의 보상. 방문을 표시하거나 기록에 사진을 붙일 때마다 본다.
	 *
	 * <p>조건을 채운 빵투어는 보상을 받았든 못 받았든 판정이 끝난 것으로 남긴다.
	 */
	public List<RewardItemResponse> rewardPhotoCompletions(Member member, LocalDate today) {
		if (member.isGuest()) {
			return List.of();
		}
		Long memberId = member.getId();
		List<RewardItemResponse> rewards = new ArrayList<>();
		for (CourseProgress progress : courseProgressRepository
			.findAllByMemberIdAndStatusAndFirstVisitedOnIsNotNullAndPhotoCompletedAtIsNull(
				memberId, CourseProgressStatus.FINISHED)) {
			Long courseId = progress.getCourse().getId();
			if (courseStoreVisitRepository.countStoresWithoutPhotoVisit(courseId, memberId) > 0) {
				continue;
			}
			progress.markPhotoCompleted(LocalDateTime.now());
			rewards.add(RewardItemResponse.tourStep(courseId, STEP_PHOTO_COMPLETE));
			rewards.add(grantOncePerWeek(memberId, CrumbReason.TOUR_PHOTO_COMPLETE,
				rewardSettings.get(RewardSettingKey.TOUR_PHOTO_COMPLETE), "tour_photo:" + courseId, courseId, today));
		}
		return rewards;
	}

	/** 이번 주(월~일)에 이 이유로 받은 적이 없을 때만 준다. 이미 받았으면 capped 로 답한다. */
	private RewardItemResponse grantOncePerWeek(
			Long memberId, CrumbReason reason, int amount, String key, Long courseId, LocalDate today) {
		crumbGrantService.serialize(memberId);
		LocalDate weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		int balance = crumbGrantService.balanceOf(memberId);
		if (ledgerRepository.existsByMemberIdAndReasonAndEarnedOnBetween(
			memberId, reason, weekStart, weekStart.plusDays(6))) {
			return RewardItemResponse.crumbs(new GrantResult(Outcome.CAPPED, 0, balance, balance), reason);
		}
		GrantResult result = crumbGrantService.grantOn(memberId, reason, amount, key, RewardRefType.COURSE, courseId,
			today);
		return RewardItemResponse.crumbs(result, reason);
	}

	/**
	 * 오늘이 이 회원에게 이 빵투어의 날인가.
	 *
	 * <p>내가 만들었거나 일행인 빵투어에 날짜가 있으면 그 날짜 ~ 끝난 다음 날이다.
	 * 날짜가 없거나 담은(남이 만든) 빵투어는 처음 다녀온 날과 그 다음 날이다 — 그래서 처음 표시는 늘 날짜 안이다.
	 */
	private boolean isWithinTourDates(Long memberId, Course course, CourseProgress progress, LocalDate today) {
		boolean mine = course.getType() == CourseType.CUSTOM
			&& (courseAccessPolicy.isOwner(course, memberId) || courseAccessPolicy.isActiveMember(course, memberId));
		if (mine && course.getStartDate() != null) {
			LocalDate lastDay = course.getEndDate() != null ? course.getEndDate() : course.getStartDate();
			return !today.isBefore(course.getStartDate()) && !today.isAfter(lastDay.plusDays(1));
		}
		LocalDate firstVisitedOn = progress.getFirstVisitedOn();
		return firstVisitedOn != null
			&& !today.isBefore(firstVisitedOn) && !today.isAfter(firstVisitedOn.plusDays(1));
	}
}
