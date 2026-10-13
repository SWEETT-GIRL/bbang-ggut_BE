package sweetgirl.croissant.reward.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.ranking.repository.RankingSnapshotJdbcRepository;
import sweetgirl.croissant.ranking.service.RankChangeResolver;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.response.RankingRewardResponse;
import sweetgirl.croissant.reward.dto.response.RewardClaimResponse;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.entity.RankingWeeklyReward;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.enums.RewardSettingKey;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.RankingWeeklyRewardRepository;

/**
 * 빵순이 랭킹 주간 보상 — 전국 주간 판(월~일)의 최종 1~10 위에게 준다.
 *
 * <p>월요일마다 지난주 순위를 읽어 보상 줄을 만들고({@link #issueFor}), 회원이 "받기"를 누르면 그때 부스러기를 준다.
 * 순위는 매일 00:05 에 남는 스냅샷에서 읽는다 — 일요일 날짜의 스냅샷이 곧 그 주의 최종 순위다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class RankingRewardService {

	static final String WEEKLY_BOARD = "WEEKLY";
	static final int TOP = 10;
	/** 1 위는 받는 주의 일요일까지 왕관을 쓴다(겨룬 주의 월요일에서 13 일 뒤). */
	static final int CROWN_DAYS = 13;

	private final RankingWeeklyRewardRepository rewardRepository;
	private final RankingSnapshotJdbcRepository snapshotRepository;
	private final MemberRestrictionReader memberRestrictionReader;
	private final CrumbGrantService crumbGrantService;
	private final RewardSettings rewardSettings;
	private final MemberReader memberReader;

	/**
	 * {@code today} 가 든 주의 지난주(월~일) 보상 줄을 만든다. 몇 번을 불러도 한 주에 한 사람 한 줄이다.
	 *
	 * <p>제재 중인 회원은 건너뛴다. 그 자리는 비워 둔다 — 다음 순위를 끌어올리면 화면의 순위와 보상이 어긋난다.
	 *
	 * @return 새로 만든 줄 수. 지난주 스냅샷이 아직 없으면 -1
	 */
	public int issueFor(LocalDate today) {
		LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDate lastWeek = thisWeek.minusWeeks(1);
		LocalDate lastSunday = thisWeek.minusDays(1);
		if (!snapshotRepository.existsForDate(lastSunday)) {
			log.warn("[랭킹 주간 보상] {} 스냅샷이 없어 만들지 못했다", lastSunday);
			return -1;
		}

		Map<Long, Integer> ranks = snapshotRepository.findRanks(
			lastSunday, WEEKLY_BOARD, 0, RankChangeResolver.NATIONWIDE);
		LocalDateTime now = LocalDateTime.now();
		int issued = 0;
		for (Map.Entry<Long, Integer> ranked : ranks.entrySet()) {
			int rank = ranked.getValue();
			Long memberId = ranked.getKey();
			if (rank < 1 || rank > TOP || memberRestrictionReader.isWriteRestricted(memberId)) {
				continue;
			}
			issued += rewardRepository.insertIfAbsent(
				lastWeek, memberId, rank, crumbsFor(rank), rank == 1 ? lastWeek.plusDays(CROWN_DAYS) : null, now);
		}
		log.info("[랭킹 주간 보상] week={} issued={}", lastWeek, issued);
		return issued;
	}

	/** 받는다. 이미 받은 것을 다시 누르면 에러가 아니라 alreadyDone 으로 답한다. */
	public RewardClaimResponse claim(Long memberId, Long rewardId) {
		memberReader.requireLoggedIn(memberId);
		LocalDate today = LocalDate.now(CrumbGrantService.ZONE);
		RankingWeeklyReward reward = rewardRepository.findByIdAndMemberId(rewardId, memberId)
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_RANKING_REWARD_NOT_FOUND));
		if (reward.isClaimed()) {
			return RewardClaimResponse.done();
		}
		if (rewardRepository.claim(rewardId, memberId, earliestClaimableWeek(today), LocalDateTime.now()) == 0) {
			// 기한이 지났거나, 방금 다른 요청이 먼저 받았다
			if (today.isAfter(reward.lastClaimableDay())) {
				throw new RewardException(RewardErrorCode.REWARD_RANKING_REWARD_EXPIRED);
			}
			return RewardClaimResponse.done();
		}
		GrantResult result = crumbGrantService.grant(
			memberId, CrumbReason.RANKING, reward.getCrumbs(), "rank:" + reward.getWeekStart(),
			RewardRefType.RANKING, rewardId);
		return new RewardClaimResponse(false,
			result.isGranted() ? List.of(RewardItemResponse.crumbs(result, CrumbReason.RANKING)) : List.of());
	}

	/** 아직 안 받았고 기한이 남은 보상 — 최근 주부터. */
	@Transactional(readOnly = true)
	public List<RankingRewardResponse> getPending(Long memberId) {
		if (memberReader.getMember(memberId).isGuest()) {
			return List.of();
		}
		return claimable(memberId).stream().map(RankingRewardResponse::from).toList();
	}

	/** 요약에 싣는 한 줄 — 받을 것 가운데 가장 최근 주. */
	@Transactional(readOnly = true)
	public Optional<RankingRewardResponse> latestPending(Long memberId) {
		return claimable(memberId).stream().findFirst().map(RankingRewardResponse::from);
	}

	/** 지금 왕관을 쓰고 있는 회원들. */
	@Transactional(readOnly = true)
	public Set<Long> crownedMemberIds() {
		return new HashSet<>(rewardRepository.findCrownedMemberIds(LocalDate.now(CrumbGrantService.ZONE)));
	}

	private List<RankingWeeklyReward> claimable(Long memberId) {
		return rewardRepository.findClaimable(memberId, earliestClaimableWeek(LocalDate.now(CrumbGrantService.ZONE)));
	}

	/** 오늘 받을 수 있는 가장 오래된 주. 그보다 앞선 주의 보상은 기한이 지났다. */
	private LocalDate earliestClaimableWeek(LocalDate today) {
		return today.minusDays(RankingWeeklyReward.CLAIMABLE_DAYS - 1L);
	}

	private int crumbsFor(int rank) {
		if (rank == 1) {
			return rewardSettings.get(RewardSettingKey.RANKING_FIRST);
		}
		return rewardSettings.get(rank <= 3 ? RewardSettingKey.RANKING_SECOND_THIRD
			: RewardSettingKey.RANKING_FOURTH_TENTH);
	}
}
