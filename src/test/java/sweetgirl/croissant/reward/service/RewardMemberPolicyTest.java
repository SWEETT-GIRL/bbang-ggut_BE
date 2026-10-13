package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.reward.enums.RewardSettingKey;

class RewardMemberPolicyTest {

	private static final LocalDateTime LAUNCH = LocalDateTime.of(2026, 11, 1, 0, 0);

	private final RewardSettings rewardSettings = mock(RewardSettings.class);

	private final RewardMemberPolicy policy = new RewardMemberPolicy(rewardSettings, "2026-11-01T00:00:00");

	@Test
	@DisplayName("출시 전에 가입했으면 기존 사용자다 — 이관 선물 대상이고 환영 선물은 아니다")
	void registeredBeforeLaunch() {
		Member member = Member.registering("청순한 포카치아", LAUNCH.minusDays(1));

		assertThat(policy.isCarryOverTarget(member)).isTrue();
		assertThat(policy.isWelcomeTarget(member)).isFalse();
	}

	@Test
	@DisplayName("출시 뒤에 가입했으면 새 회원이다")
	void registeredAfterLaunch() {
		Member member = Member.registering("청순한 포카치아", LAUNCH.plusMinutes(1));

		assertThat(policy.isCarryOverTarget(member)).isFalse();
		assertThat(policy.isWelcomeTarget(member)).isTrue();
	}

	@Test
	@DisplayName("출시 전부터 게스트였어도 출시 뒤에 로그인했으면 새 회원이다")
	void guestPromotedAfterLaunch() {
		Member member = Member.builder().nickname("청순한 포카치아").build();
		member.markAsGuest();
		ReflectionTestUtils.setField(member, "createdAt", LAUNCH.minusMonths(2));
		member.promoteToUser(LAUNCH.plusDays(3));

		assertThat(policy.isCarryOverTarget(member)).isFalse();
		assertThat(policy.isWelcomeTarget(member)).isTrue();
	}

	@Test
	@DisplayName("출시 뒤에 재가입한 회원은 이관 선물도 환영 선물도 받지 않는다")
	void rejoinedAfterLaunch() {
		Member member = Member.rejoining("청순한 포카치아", 41L, LAUNCH.plusDays(3));

		assertThat(policy.isCarryOverTarget(member)).isFalse();
		assertThat(policy.isWelcomeTarget(member)).isFalse();
	}

	@Test
	@DisplayName("게스트는 어느 쪽도 아니다")
	void guest() {
		Member member = Member.builder().nickname("청순한 포카치아").build();
		member.markAsGuest();
		ReflectionTestUtils.setField(member, "createdAt", LAUNCH.minusMonths(2));

		assertThat(policy.isCarryOverTarget(member)).isFalse();
		assertThat(policy.isWelcomeTarget(member)).isFalse();
	}

	@Test
	@DisplayName("출시 시각을 넣지 않으면 모두 기존 사용자로 본다")
	void defaultsToEveryoneExisting() {
		RewardMemberPolicy unset = new RewardMemberPolicy(rewardSettings, "9999-12-31T00:00:00");
		Member member = Member.registering("청순한 포카치아", LocalDateTime.now());

		assertThat(unset.isCarryOverTarget(member)).isTrue();
	}

	@Test
	@DisplayName("어드민이 넣은 출시 시각이 설정 파일의 값보다 먼저다")
	void adminCutoffWins() {
		given(rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_DATE)).willReturn(20261201);
		given(rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_TIME)).willReturn(1430);
		LocalDateTime cutoff = LocalDateTime.of(2026, 12, 1, 14, 30);

		assertThat(policy.carryOverBefore()).isEqualTo(cutoff);
		assertThat(policy.isCarryOverTarget(Member.registering("청순한 포카치아", cutoff.minusMinutes(1)))).isTrue();
		assertThat(policy.isWelcomeTarget(Member.registering("청순한 포카치아", cutoff))).isTrue();
	}

	@Test
	@DisplayName("어드민이 넣은 값이 없는 날짜면 설정 파일의 값으로 물러선다")
	void brokenAdminCutoffFallsBack() {
		given(rewardSettings.get(RewardSettingKey.CARRY_OVER_BEFORE_DATE)).willReturn(20261341);

		assertThat(policy.carryOverBefore()).isEqualTo(LAUNCH);
	}

	@Test
	@DisplayName("날짜가 0 이면 안 정한 것이다")
	void zeroDateMeansUnset() {
		assertThat(RewardMemberPolicy.cutoffOf(0, 1430)).isEmpty();
	}
}
