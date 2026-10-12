package sweetgirl.croissant.reward.service;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import sweetgirl.croissant.member.entity.Member;

/**
 * 이 회원이 1.x 에서 넘어온 사람인가, 2.0 에서 새로 온 사람인가.
 *
 * <p>가르는 기준은 <b>소셜로 가입한 시각</b>이 2.0 출시 시각보다 앞인가다. 게스트로만 쓰다 2.0 에서 처음 로그인한 사람은
 * 새 회원이다 — 그래서 {@code createdAt}(게스트 발급 시각)이 아니라 {@code registeredAt} 을 본다.
 *
 * <p>출시 시각은 {@code reward.carry-over-before} 로 준다(한국 시간). <b>값을 넣지 않으면 모두 기존 사용자로 본다</b> —
 * 설정을 빠뜨렸을 때 기존 사용자가 이관 선물 대신 환영 선물을 받는 쪽보다, 새 회원이 이관 선물을 받는 쪽이 덜 아프다.
 */
@Component
public class RewardMemberPolicy {

	private final LocalDateTime carryOverBefore;

	public RewardMemberPolicy(@Value("${reward.carry-over-before:9999-12-31T00:00:00}") String carryOverBefore) {
		this.carryOverBefore = LocalDateTime.parse(carryOverBefore);
	}

	/** 이관 선물 대상(기존 사용자)인가. 게스트는 아니다. */
	public boolean isCarryOverTarget(Member member) {
		if (member.isGuest()) {
			return false;
		}
		LocalDateTime registeredAt = member.getRegisteredAt() != null
			? member.getRegisteredAt()
			: member.getCreatedAt();
		return registeredAt.isBefore(carryOverBefore);
	}

	/** 환영 선물 대상인가 — 2.0 에서 새로 온, 재가입이 아닌 회원. */
	public boolean isWelcomeTarget(Member member) {
		return !member.isGuest() && !isCarryOverTarget(member) && !member.isRejoined();
	}
}
