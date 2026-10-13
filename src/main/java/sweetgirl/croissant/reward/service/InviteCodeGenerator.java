package sweetgirl.croissant.reward.service;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 초대 코드를 만든다. 손으로 옮겨 적기 쉽게, 헷갈리는 글자(0 · O · 1 · I)는 쓰지 않는다. */
@Component
@RequiredArgsConstructor
public class InviteCodeGenerator {

	static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	static final int LENGTH = 8;

	private final RewardRandom rewardRandom;

	public String generate() {
		StringBuilder code = new StringBuilder(LENGTH);
		for (int index = 0; index < LENGTH; index++) {
			code.append(ALPHABET.charAt(rewardRandom.nextInt(ALPHABET.length())));
		}
		return code.toString();
	}
}
