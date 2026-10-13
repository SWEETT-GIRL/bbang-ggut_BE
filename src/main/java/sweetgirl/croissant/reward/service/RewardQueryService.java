package sweetgirl.croissant.reward.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.RewardHistoryResponse;
import sweetgirl.croissant.reward.dto.response.RewardRulesResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Ad;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Attendance;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Feed;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.RankingReward;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.Welcome;
import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberAttendanceDayRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.repository.MemberFeedDayRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RewardQueryService {

	static final int MAX_HISTORY_SIZE = 50;

	private final MemberCrumbWalletRepository walletRepository;
	private final CrumbLedgerRepository ledgerRepository;
	private final RewardPendingService rewardPendingService;
	private final RewardSettings rewardSettings;
	private final RewardMemberPolicy rewardMemberPolicy;
	private final MemberReader memberReader;
	private final MemberAttendanceDayRepository attendanceDayRepository;
	private final MemberFeedDayRepository feedDayRepository;
	private final BbangdaengLetterService bbangdaengLetterService;
	private final EarnRewardService earnRewardService;
	private final RankingRewardService rankingRewardService;

	/**
	 * 홈이 한 번에 읽는 요약.
	 *
	 * @param carryOver 이 요청에서 방금 이관 선물이 들어갔으면 그 내용, 아니면 null
	 */
	public RewardSummaryResponse getSummary(Long memberId, CarryOver carryOver) {
		Member member = memberReader.getMember(memberId);
		RewardRulesResponse rules = RewardRulesResponse.from(rewardSettings);
		LocalDate today = LocalDate.now(CrumbGrantService.ZONE);
		if (member.isGuest()) {
			return RewardSummaryResponse.guest(rules,
				attendanceOf(AttendanceBoard.of(Optional.empty(), Optional.empty())),
				new Feed(false, rewardSettings.feedWedges()));
		}
		boolean welcomeAvailable = rewardMemberPolicy.isWelcomeTarget(member)
			&& !ledgerRepository.existsByMemberIdAndIdempotencyKey(memberId, RewardCommandService.WELCOME_KEY);
		return new RewardSummaryResponse(
			walletRepository.findBalance(memberId).orElse(0),
			ledgerRepository.sumGainedOn(memberId, today),
			rules,
			carryOver,
			new Welcome(welcomeAvailable),
			rewardPendingService.list(memberId),
			attendanceOf(AttendanceBoard.of(
				attendanceDayRepository.findByMemberIdAndDay(memberId, today.minusDays(1)),
				attendanceDayRepository.findByMemberIdAndDay(memberId, today))),
			new Feed(feedDayRepository.findByMemberIdAndDay(memberId, today).isPresent(),
				rewardSettings.feedWedges()),
			bbangdaengLetterService.summaryOf(memberId),
			new Ad(earnRewardService.adsLeftToday(memberId)),
			rankingRewardService.latestPending(memberId)
				.map(reward -> new RankingReward(reward.id(), reward.rank(), reward.crumbs()))
				.orElse(null));
	}

	/** 판의 자리에 보상 수치를 붙인다. 오늘 했으면 오늘 칸은 찍힌 칸이고, 안 했으면 찍을 칸이다. */
	private Attendance attendanceOf(AttendanceBoard board) {
		List<Integer> rewards = rewardSettings.attendance();
		int todayBoardDay = board.doneToday() ? board.stampedDays() : board.nextBoardDay();
		return new Attendance(
			board.doneToday(),
			board.stampedDays(),
			board.nextBoardDay(),
			AttendanceBoard.rewardOf(todayBoardDay, rewards),
			AttendanceBoard.rewardOf(AttendanceBoard.following(todayBoardDay), rewards));
	}

	/** 받은 내역. 한 장 더 읽어 보고 다음 장이 있는지 가린다. */
	public RewardHistoryResponse getHistory(Long memberId, Long cursor, int size) {
		if (memberReader.getMember(memberId).isGuest()) {
			return new RewardHistoryResponse(List.of(), null);
		}
		int pageSize = Math.max(1, Math.min(size, MAX_HISTORY_SIZE));
		Pageable onePageAndOne = PageRequest.of(0, pageSize + 1);
		List<CrumbLedgerEntry> entries = cursor == null
			? ledgerRepository.findByMemberIdOrderByIdDesc(memberId, onePageAndOne)
			: ledgerRepository.findByMemberIdAndIdLessThanOrderByIdDesc(memberId, cursor, onePageAndOne);

		boolean hasNext = entries.size() > pageSize;
		List<CrumbLedgerEntry> page = hasNext ? entries.subList(0, pageSize) : entries;
		Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
		return new RewardHistoryResponse(page.stream().map(RewardHistoryResponse.Item::from).toList(), nextCursor);
	}
}
