package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.RewardRefType;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.CrumbLedgerRepository;
import sweetgirl.croissant.reward.repository.MemberCrumbWalletRepository;
import sweetgirl.croissant.reward.service.GrantResult.Outcome;

@ExtendWith(MockitoExtension.class)
class CrumbGrantServiceTest {

	private static final long MEMBER_ID = 7L;
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

	@Mock
	private MemberCrumbWalletRepository walletRepository;

	@Mock
	private CrumbLedgerRepository ledgerRepository;

	@Mock
	private MemberRestrictionReader memberRestrictionReader;

	@InjectMocks
	private CrumbGrantService crumbGrantService;

	private void givenBalance(int balance) {
		given(walletRepository.lockBalance(MEMBER_ID)).willReturn(Optional.of(balance));
	}

	private void givenLedgerInsert(int insertedRows) {
		given(ledgerRepository.insertIfAbsent(
			anyLong(), anyInt(), anyString(), anyString(), anyInt(), any(), any(), any(), any(), any()))
			.willReturn(insertedRows);
	}

	@Nested
	@DisplayName("적립")
	class Grant {

		@Test
		@DisplayName("처음 주는 보상이면 원장에 남기고 잔액을 올린다")
		void success() {
			givenBalance(270);
			givenLedgerInsert(1);

			GrantResult result = crumbGrantService.grant(
				MEMBER_ID, CrumbReason.RECORD, 10, "record:55", RewardRefType.RECORD, 55L);

			assertThat(result).isEqualTo(new GrantResult(Outcome.GRANTED, 10, 270, 280));
			then(ledgerRepository).should().insertIfAbsent(
				eq(MEMBER_ID), eq(10), eq("RECORD"), eq("record:55"), eq(280), any(), eq("RECORD"), eq(55L),
				isNull(), any());
			then(walletRepository).should().addBalance(eq(MEMBER_ID), eq(10), any());
		}

		@Test
		@DisplayName("같은 키로 이미 줬으면 잔액을 건드리지 않는다")
		void alreadyGranted() {
			givenBalance(280);
			givenLedgerInsert(0);

			GrantResult result = crumbGrantService.grant(
				MEMBER_ID, CrumbReason.RECORD, 10, "record:55", RewardRefType.RECORD, 55L);

			assertThat(result).isEqualTo(new GrantResult(Outcome.ALREADY_GRANTED, 0, 280, 280));
			then(walletRepository).should(never()).addBalance(anyLong(), anyInt(), any());
		}

		@Test
		@DisplayName("제재 중인 회원에게는 원장에 쓰지 않고 0 으로 답한다")
		void restricted() {
			givenBalance(280);
			given(memberRestrictionReader.isWriteRestricted(MEMBER_ID)).willReturn(true);

			GrantResult result = crumbGrantService.grant(
				MEMBER_ID, CrumbReason.RECORD, 10, "record:56", RewardRefType.RECORD, 56L);

			assertThat(result).isEqualTo(new GrantResult(Outcome.RESTRICTED, 0, 280, 280));
			then(ledgerRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("하루 상한에 닿았으면 주지 않고 CAPPED 로 답한다")
		void capped() {
			givenBalance(300);
			given(ledgerRepository.countByMemberIdAndReasonAndEarnedOn(MEMBER_ID, CrumbReason.RECORD, TODAY))
				.willReturn(3L);

			GrantResult result = crumbGrantService.grantWithDailyCap(
				MEMBER_ID, CrumbReason.RECORD, 10, "record:57", RewardRefType.RECORD, 57L, 3, TODAY);

			assertThat(result.isCapped()).isTrue();
			assertThat(result.amount()).isZero();
			then(walletRepository).should(never()).addBalance(anyLong(), anyInt(), any());
		}

		@Test
		@DisplayName("상한 아래면 준다")
		void underCap() {
			givenBalance(290);
			given(ledgerRepository.countByMemberIdAndReasonAndEarnedOn(MEMBER_ID, CrumbReason.RECORD, TODAY))
				.willReturn(2L);
			givenLedgerInsert(1);

			GrantResult result = crumbGrantService.grantWithDailyCap(
				MEMBER_ID, CrumbReason.RECORD, 10, "record:58", RewardRefType.RECORD, 58L, 3, TODAY);

			assertThat(result).isEqualTo(new GrantResult(Outcome.GRANTED, 10, 290, 300));
		}

		@Test
		@DisplayName("0 이하를 주려 하면 REWARD400_1")
		void invalidAmount() {
			assertThatThrownBy(() -> crumbGrantService.grant(
				MEMBER_ID, CrumbReason.RECORD, 0, "record:59", RewardRefType.RECORD, 59L))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_AMOUNT_INVALID);
		}

		@Test
		@DisplayName("어드민 지급은 제재 중인 회원에게도 들어가고 사유를 남긴다")
		void byAdmin() {
			givenBalance(0);
			givenLedgerInsert(1);

			GrantResult result = crumbGrantService.grantByAdmin(MEMBER_ID, 100, "admin:abc", "광고 오류 보상");

			assertThat(result).isEqualTo(new GrantResult(Outcome.GRANTED, 100, 0, 100));
			then(memberRestrictionReader).shouldHaveNoInteractions();
			then(ledgerRepository).should().insertIfAbsent(
				eq(MEMBER_ID), eq(100), eq("ADMIN_GRANT"), eq("admin:abc"), eq(100), any(), isNull(), isNull(),
				eq("광고 오류 보상"), any());
		}
	}

	@Nested
	@DisplayName("차감")
	class Spend {

		@Test
		@DisplayName("잔액이 충분하면 원장에 음수로 남기고 잔액을 내린다")
		void success() {
			givenBalance(700);
			givenLedgerInsert(1);

			SpendResult result = crumbGrantService.spend(MEMBER_ID, CrumbReason.WAKE_FRIEND, 600, "wake:cat");

			assertThat(result).isEqualTo(new SpendResult(true, 600, 700, 100));
			then(ledgerRepository).should().insertIfAbsent(
				eq(MEMBER_ID), eq(-600), eq("WAKE_FRIEND"), eq("wake:cat"), eq(100), any(), isNull(), isNull(),
				isNull(), any());
			then(walletRepository).should().addBalance(eq(MEMBER_ID), eq(-600), any());
		}

		@Test
		@DisplayName("잔액이 모자라면 REWARD409_1 이고 원장에 쓰지 않는다")
		void insufficient() {
			givenBalance(599);

			assertThatThrownBy(
				() -> crumbGrantService.spend(MEMBER_ID, CrumbReason.WAKE_FRIEND, 600, "wake:cat"))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT);
			then(ledgerRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("같은 키로 이미 뺐으면 다시 빼지 않는다")
		void alreadySpent() {
			givenBalance(100);
			givenLedgerInsert(0);

			SpendResult result = crumbGrantService.spend(MEMBER_ID, CrumbReason.SHOP_PURCHASE, 90, "shop:dog:pose-joy");

			assertThat(result).isEqualTo(new SpendResult(false, 0, 100, 100));
			then(walletRepository).should(never()).addBalance(anyLong(), anyInt(), any());
		}

		@Test
		@DisplayName("어드민 회수는 잔액보다 크게 달라고 해도 0 까지만 뺀다")
		void revokeUpToBalance() {
			givenBalance(40);
			givenLedgerInsert(1);

			SpendResult result = crumbGrantService.revokeByAdmin(MEMBER_ID, 100, "admin:def", "오지급 회수");

			assertThat(result).isEqualTo(new SpendResult(true, 40, 40, 0));
		}

		@Test
		@DisplayName("잔액이 0 이면 어드민 회수는 아무것도 남기지 않는다")
		void revokeNothing() {
			givenBalance(0);

			SpendResult result = crumbGrantService.revokeByAdmin(MEMBER_ID, 100, "admin:ghi", "오지급 회수");

			assertThat(result.spent()).isFalse();
			then(ledgerRepository).shouldHaveNoInteractions();
		}
	}
}
