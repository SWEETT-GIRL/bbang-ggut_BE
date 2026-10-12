package sweetgirl.croissant.reward.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** 보상을 뽑는 난수. 테스트가 결과를 정할 수 있게 한 겹 감쌌다. */
@Component
public class RewardRandom {

	private final SecureRandom secureRandom = new SecureRandom();

	/** 0 이상 {@code bound} 미만. */
	public int nextInt(int bound) {
		return secureRandom.nextInt(bound);
	}
}
