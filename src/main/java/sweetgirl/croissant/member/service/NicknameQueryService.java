package sweetgirl.croissant.member.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.dto.response.NicknameAvailabilityResponse;

/** 닉네임 입력 칸의 실시간 ✓/✕. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NicknameQueryService {

	static final int SUGGESTION_COUNT = 3;
	private static final int SUGGESTION_TRIES = 20;

	private final NicknamePolicy nicknamePolicy;

	public NicknameAvailabilityResponse checkAvailability(String rawNickname) {
		String nickname = rawNickname == null ? "" : rawNickname.strip();
		if (!nicknamePolicy.isWellFormed(nickname)) {
			return NicknameAvailabilityResponse.rejected("FORMAT", List.of());
		}
		if (nicknamePolicy.isForbidden(nickname)) {
			return NicknameAvailabilityResponse.rejected("FORBIDDEN", List.of());
		}
		if (nicknamePolicy.isTaken(nickname)) {
			return NicknameAvailabilityResponse.rejected("TAKEN", suggest(nickname));
		}
		return NicknameAvailabilityResponse.ok();
	}

	/** 뒤에 숫자 두 자리를 붙여 빈 이름을 찾는다. 10자를 넘기면 앞을 잘라 자리를 만든다. */
	private List<String> suggest(String nickname) {
		String base = nickname.length() + 2 > NicknamePolicy.MAX_LENGTH
			? nickname.substring(0, NicknamePolicy.MAX_LENGTH - 2) : nickname;
		List<String> suggestions = new ArrayList<>();
		for (int tried = 0; tried < SUGGESTION_TRIES && suggestions.size() < SUGGESTION_COUNT; tried++) {
			String candidate = base + String.format("%02d", ThreadLocalRandom.current().nextInt(100));
			if (!suggestions.contains(candidate) && !nicknamePolicy.isTaken(candidate)
				&& !nicknamePolicy.isForbidden(candidate)) {
				suggestions.add(candidate);
			}
		}
		return suggestions;
	}
}
