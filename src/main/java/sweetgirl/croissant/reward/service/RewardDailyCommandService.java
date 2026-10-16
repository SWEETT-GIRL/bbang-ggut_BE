package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.AttendanceResponse;
import sweetgirl.croissant.reward.dto.response.FeedResponse;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.entity.MemberAttendanceDay;
import sweetgirl.croissant.reward.entity.MemberFeedDay;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.repository.MemberAttendanceDayRepository;
import sweetgirl.croissant.reward.repository.MemberFeedDayRepository;

/**
 * 하루에 한 번 하는 보상 — 출석 7일판과 밥 룰렛.
 *
 * <p>날짜는 요청을 받은 순간의 한국 날짜 하나를 끝까지 쓴다. 중간에 다시 읽으면 자정을 걸친 요청이 어제 칸에 오늘 보상을 받는다.
 * 같은 날 두 번째 요청은 에러가 아니다 — 앱이 두 번 누를 수 있어서, 오늘 결과를 그대로 돌려준다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class RewardDailyCommandService {

	/** 연속 기록 일수를 셀 때 거슬러 올라가는 한도. 출석판 자리는 7 로 나눈 나머지라 이보다 긴 연속은 자리만 달라진다. */
	private static final int STREAK_LOOKBACK_DAYS = 400;

	private final CrumbGrantService crumbGrantService;
	private final RewardSettings rewardSettings;
	private final RewardRandom rewardRandom;
	private final MemberAttendanceDayRepository attendanceDayRepository;
	private final MemberFeedDayRepository feedDayRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final MemberReader memberReader;

	/** 오늘 출석한다. 어제에 이어지면 다음 칸, 끊겼거나 어제가 7 일째였으면 1 일째다. */
	public AttendanceResponse checkIn(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		LocalDate today = LocalDate.now(CrumbGrantService.ZONE);

		AttendanceBoard board = AttendanceBoard.of(
			attendanceDayRepository.findByMemberIdAndDay(memberId, today.minusDays(1)), Optional.empty());
		int boardDay = board.nextBoardDay();
		int reward = AttendanceBoard.rewardOf(boardDay, rewardSettings.attendance());

		if (attendanceDayRepository.insertIfAbsent(memberId, today, boardDay, reward, LocalDateTime.now()) == 0) {
			MemberAttendanceDay done = attendanceDayRepository.findByMemberIdAndDay(memberId, today).orElseThrow();
			return new AttendanceResponse(done.getBoardDay(), done.getCrumbs(), true, List.of());
		}

		GrantResult result = crumbGrantService.grantOn(
			memberId, CrumbReason.ATTENDANCE, reward, "attendance:" + today, today);
		if (!result.isGranted()) {
			// 제재 중이면 도장은 찍히되 부스러기는 없다 — 판이 끊기지는 않는다
			attendanceDayRepository.updateCrumbs(memberId, today, 0);
			return new AttendanceResponse(boardDay, 0, false, List.of());
		}
		return new AttendanceResponse(boardDay, reward, false,
			List.of(RewardItemResponse.crumbs(result, CrumbReason.ATTENDANCE)));
	}

	/**
	 * 오늘의 밥 룰렛을 돌린다. 값을 가중치로 뽑고, 그 값이 적힌 칸 중 하나에 멈춘다.
	 *
	 * <p>하루 한 판은 무료다. 무료 판을 쓴 뒤에는 광고로 연 판({@link #openFeedExtra})이 있을 때만 한 판 더 돌고,
	 * 그것도 없으면 오늘 결과를 그대로 돌려준다.
	 */
	public FeedResponse feed(Long memberId) {
		memberReader.requireLoggedIn(memberId);
		LocalDate today = LocalDate.now(CrumbGrantService.ZONE);

		List<Integer> wedges = rewardSettings.feedWedges();
		int wedge = pickWedge(wedges, rewardSettings.feedWeights());
		int reward = wedges.get(wedge);

		int extraCap = rewardSettings.get(RewardSettingKey.FEED_AD_DAILY_CAP);
		if (feedDayRepository.insertIfAbsent(memberId, today, wedge, reward, extraCap, LocalDateTime.now()) == 0) {
			if (feedDayRepository.closeExtraIfReady(memberId, today) == 0) {
				MemberFeedDay done = feedDayRepository.findByMemberIdAndDay(memberId, today).orElseThrow();
				return new FeedResponse(done.getWedge(), done.getCrumbs(), true, List.of());
			}
			// 광고로 연 한 판. 멱등 키는 남은 횟수로 가른다 — 하루 안에서 줄어들기만 하니 겹치지 않는다
			int extraLeft = feedDayRepository.findByMemberIdAndDay(memberId, today).orElseThrow().getExtraLeft();
			return spin(memberId, today, wedge, reward, "feed:" + today + ":extra:" + extraLeft);
		}

		FeedResponse response = spin(memberId, today, wedge, reward, "feed:" + today);
		if (response.crumbs() == 0) {
			feedDayRepository.updateCrumbs(memberId, today, 0);
		}
		return response;
	}

	/**
	 * 광고를 끝까지 봤으니 오늘 한 판을 더 연다. 구글의 서버 검증 콜백에서만 부른다 — 앱의 요청으로는 열지 않는다.
	 *
	 * @return 열었는가. 오늘 무료 판을 아직 안 돌렸거나, 횟수를 다 썼거나, 이미 열린 판이 있으면 false
	 */
	public boolean openFeedExtra(Long memberId) {
		return feedDayRepository.openExtraIfLeft(memberId, LocalDate.now(CrumbGrantService.ZONE)) == 1;
	}

	/** 멈춘 칸의 값을 준다. 제재 중이거나 칸의 값이 0 이면 부스러기 없이 칸만 돌려준다. */
	private FeedResponse spin(Long memberId, LocalDate today, int wedge, int reward, String idempotencyKey) {
		GrantResult result = reward > 0
			? crumbGrantService.grantOn(memberId, CrumbReason.FEED, reward, idempotencyKey, today)
			: null;
		if (result == null || !result.isGranted()) {
			return new FeedResponse(wedge, 0, false, List.of());
		}
		return new FeedResponse(wedge, reward, false, List.of(RewardItemResponse.crumbs(result, CrumbReason.FEED)));
	}

	/**
	 * 1.x 의 연속 기록 일수를 출석판 자리로 옮긴다. 이관 선물을 줄 때 한 번 부른다.
	 *
	 * <p>연속 {@code s} 일이면 <b>어제</b> 날짜로 {@code ((s - 1) % 7) + 1} 번째 칸을 부스러기 0 으로 찍어 둔다 —
	 * 오늘 출석하면 그다음 칸으로 이어진다. 연속이 없으면 아무것도 하지 않는다(1 일째부터).
	 */
	public void seedAttendanceFromRecordStreak(Long memberId, LocalDate today) {
		int streak = recordStreak(memberId, today);
		if (streak == 0) {
			return;
		}
		int boardDay = ((streak - 1) % AttendanceBoard.BOARD_SIZE) + 1;
		attendanceDayRepository.insertIfAbsent(memberId, today.minusDays(1), boardDay, 0, LocalDateTime.now());
	}

	/** 오늘(없으면 어제)부터 거꾸로, 기록을 남긴 날이 끊기지 않은 만큼. 1.x 홈의 "N일 연속"과 같은 계산이다. */
	private int recordStreak(Long memberId, LocalDate today) {
		Set<LocalDate> recordedDays = new HashSet<>();
		breadRecordRepository.findRecordedDaysSince(memberId, today.minusDays(STREAK_LOOKBACK_DAYS).atStartOfDay())
			.forEach(day -> recordedDays.add(LocalDate.parse(day)));

		LocalDate cursor = recordedDays.contains(today) ? today : today.minusDays(1);
		int streak = 0;
		while (recordedDays.contains(cursor)) {
			streak++;
			cursor = cursor.minusDays(1);
		}
		return streak;
	}

	/**
	 * 멈출 칸을 고른다. 가중치로 값을 뽑은 뒤 그 값이 적힌 칸 중 하나를 고르게 고른다.
	 *
	 * <p>어드민이 칸의 값을 바꿔 가중치가 가리키는 값이 판에 하나도 없으면, 칸을 고르게 뽑는다 — 룰렛이 멈출 곳은 늘 있어야 한다.
	 */
	private int pickWedge(List<Integer> wedges, Map<Integer, Integer> weights) {
		List<Integer> values = new ArrayList<>();
		int totalWeight = 0;
		for (Map.Entry<Integer, Integer> weight : weights.entrySet()) {
			if (weight.getValue() > 0 && wedges.contains(weight.getKey())) {
				values.add(weight.getKey());
				totalWeight += weight.getValue();
			}
		}
		if (totalWeight == 0) {
			return rewardRandom.nextInt(wedges.size());
		}

		int roll = rewardRandom.nextInt(totalWeight);
		int picked = values.get(values.size() - 1);
		for (int value : values) {
			roll -= weights.get(value);
			if (roll < 0) {
				picked = value;
				break;
			}
		}

		List<Integer> candidates = new ArrayList<>();
		for (int index = 0; index < wedges.size(); index++) {
			if (wedges.get(index) == picked) {
				candidates.add(index);
			}
		}
		return candidates.get(rewardRandom.nextInt(candidates.size()));
	}
}
