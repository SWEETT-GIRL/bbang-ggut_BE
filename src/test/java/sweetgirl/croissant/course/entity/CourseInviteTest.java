package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;

@DisplayName("CourseInvite")
class CourseInviteTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 3, 12, 0);

	private CourseInvite inviteExpiringAt(LocalDateTime expiresAt) {
		return CourseInvite.of(mock(Course.class), mock(Member.class), "token", expiresAt);
	}

	@Test
	@DisplayName("만료 전이고 무효화되지 않았으면 활성이다")
	void isActive_beforeExpiry() {
		CourseInvite invite = inviteExpiringAt(NOW.plusDays(7));

		assertThat(invite.isExpired(NOW)).isFalse();
		assertThat(invite.isActive(NOW)).isTrue();
	}

	@Test
	@DisplayName("만료 시각과 같거나 지났으면 만료다")
	void isExpired_atOrAfterExpiry() {
		assertThat(inviteExpiringAt(NOW).isExpired(NOW)).isTrue();
		assertThat(inviteExpiringAt(NOW.minusSeconds(1)).isExpired(NOW)).isTrue();
		assertThat(inviteExpiringAt(NOW).isActive(NOW)).isFalse();
	}

	@Test
	@DisplayName("revoke 하면 만료 전이어도 활성이 아니다")
	void revoke_deactivates() {
		CourseInvite invite = inviteExpiringAt(NOW.plusDays(7));

		invite.revoke();

		assertThat(invite.getDeletedAt()).isNotNull();
		assertThat(invite.isActive(NOW)).isFalse();
		assertThat(invite.isExpired(NOW)).isFalse();
	}
}
