package sweetgirl.croissant.member.dto.response;

import java.util.List;

/**
 * 닉네임을 쓸 수 있는지. {@code reason} 은 안 될 때만: FORMAT(형식) · FORBIDDEN(금칙어) · TAKEN(이미 있음).
 * TAKEN 이면 {@code suggestions} 에 빈 이름 후보를 담는다.
 */
public record NicknameAvailabilityResponse(boolean available, String reason, List<String> suggestions) {

	public static NicknameAvailabilityResponse ok() {
		return new NicknameAvailabilityResponse(true, null, List.of());
	}

	public static NicknameAvailabilityResponse rejected(String reason, List<String> suggestions) {
		return new NicknameAvailabilityResponse(false, reason, suggestions);
	}
}
