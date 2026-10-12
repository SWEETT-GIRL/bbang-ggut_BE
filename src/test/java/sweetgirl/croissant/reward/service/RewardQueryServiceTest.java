package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.RewardHistoryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse;
import sweetgirl.croissant.reward.dto.response.RewardSummaryResponse.CarryOver;
import sweetgirl.croissant.reward.entity.CrumbLedgerEntry;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;

@ExtendWith(MockitoExtension.class)
class RewardQueryServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private MemberCrumbWalletRepository walletRepository;
	@Mock
	private CrumbLedgerRepository ledgerRepository;
	@Mock
	private RewardPendingService rewardPendingService;
	@Mock
	private RewardSettings rewardSettings;
	@Mock
	private RewardMemberPolicy rewardMemberPolicy;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private RewardQueryService rewardQueryService;

	private Member member;

	@BeforeEach
	void setUp() {
		member = mock(Member.class);
		given(memberReader.getMember(MEMBER_ID)).willReturn(member);
	}

	@Nested
	@DisplayName("요약")
	class GetSummary {

		@Test
		@DisplayName("회원은 잔액 · 오늘 받은 양 · 방금 들어간 이관 선물을 본다")
		void member() {
			given(walletRepository.findBalance(MEMBER_ID)).willReturn(Optional.of(285));
			given(ledgerRepository.sumGainedOn(eq(MEMBER_ID), any())).willReturn(26L);
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(false);
			given(rewardPendingService.list(MEMBER_ID)).willReturn(List.of());
			CarryOver carryOver = new CarryOver(true, 110, 12);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, carryOver);

			assertThat(summary.crumbs()).isEqualTo(285);
			assertThat(summary.gainedToday()).isEqualTo(26);
			assertThat(summary.carryOver()).isEqualTo(carryOver);
			assertThat(summary.welcome().available()).isFalse();
		}

		@Test
		@DisplayName("아직 환영 선물을 안 받은 새 회원은 welcome.available 이 true 다")
		void welcomeAvailable() {
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(true);
			given(ledgerRepository.existsByMemberIdAndIdempotencyKey(MEMBER_ID, "welcome")).willReturn(false);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.welcome().available()).isTrue();
			assertThat(summary.crumbs()).isZero();
			assertThat(summary.carryOver()).isNull();
		}

		@Test
		@DisplayName("환영 선물을 받은 뒤에는 available 이 false 다")
		void welcomeTaken() {
			given(rewardMemberPolicy.isWelcomeTarget(member)).willReturn(true);
			given(ledgerRepository.existsByMemberIdAndIdempotencyKey(MEMBER_ID, "welcome")).willReturn(true);

			assertThat(rewardQueryService.getSummary(MEMBER_ID, null).welcome().available()).isFalse();
		}

		@Test
		@DisplayName("게스트는 에러가 아니라 빈 요약을 받는다 — 지갑도 원장도 읽지 않는다")
		void guest() {
			given(member.isGuest()).willReturn(true);

			RewardSummaryResponse summary = rewardQueryService.getSummary(MEMBER_ID, null);

			assertThat(summary.crumbs()).isZero();
			assertThat(summary.welcome().available()).isFalse();
			assertThat(summary.pendingRewards()).isEmpty();
			assertThat(summary.rules()).isNotNull();
			then(walletRepository).shouldHaveNoInteractions();
			then(ledgerRepository).shouldHaveNoInteractions();
		}
	}

	@Nested
	@DisplayName("받은 내역")
	class GetHistory {

		private List<CrumbLedgerEntry> entries(long newestId, int count) {
			List<CrumbLedgerEntry> entries = new ArrayList<>();
			for (int index = 0; index < count; index++) {
				// 한 장 더 읽은 마지막 줄은 응답에 안 실려 id 를 읽히지 않는다
				CrumbLedgerEntry entry = mock(CrumbLedgerEntry.class, withSettings().strictness(Strictness.LENIENT));
				given(entry.getId()).willReturn(newestId - index);
				entries.add(entry);
			}
			return entries;
		}

		@Test
		@DisplayName("한 장을 넘게 읽히면 다음 장 커서를 준다")
		void hasNextPage() {
			List<CrumbLedgerEntry> threeEntries = entries(100, 3);
			given(ledgerRepository.findByMemberIdOrderByIdDesc(MEMBER_ID, PageRequest.of(0, 3)))
				.willReturn(threeEntries);

			RewardHistoryResponse history = rewardQueryService.getHistory(MEMBER_ID, null, 2);

			assertThat(history.items()).extracting(RewardHistoryResponse.Item::id).containsExactly(100L, 99L);
			assertThat(history.nextCursor()).isEqualTo(99L);
		}

		@Test
		@DisplayName("마지막 장이면 커서가 없다")
		void lastPage() {
			List<CrumbLedgerEntry> twoEntries = entries(98, 2);
			given(ledgerRepository.findByMemberIdAndIdLessThanOrderByIdDesc(MEMBER_ID, 99L, PageRequest.of(0, 3)))
				.willReturn(twoEntries);

			RewardHistoryResponse history = rewardQueryService.getHistory(MEMBER_ID, 99L, 2);

			assertThat(history.items()).hasSize(2);
			assertThat(history.nextCursor()).isNull();
		}

		@Test
		@DisplayName("한 장은 50 개를 넘지 않는다")
		void clampsSize() {
			given(ledgerRepository.findByMemberIdOrderByIdDesc(MEMBER_ID, PageRequest.of(0, 51)))
				.willReturn(List.of());

			assertThat(rewardQueryService.getHistory(MEMBER_ID, null, 5000).items()).isEmpty();
		}

		@Test
		@DisplayName("게스트는 빈 내역이다")
		void guest() {
			given(member.isGuest()).willReturn(true);

			assertThat(rewardQueryService.getHistory(MEMBER_ID, null, 30).items()).isEmpty();
			then(ledgerRepository).shouldHaveNoInteractions();
		}
	}
}
