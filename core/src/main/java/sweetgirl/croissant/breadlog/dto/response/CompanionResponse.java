package sweetgirl.croissant.breadlog.dto.response;

import sweetgirl.croissant.breadlog.entity.BreadRecordCompanion;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

/** 빵로그 상세의 같이 간 사람 한 줄. */
public record CompanionResponse(
		Long memberId,
		String nickname,
		String characterCode,
		String characterImageKey) {
	public static CompanionResponse of(BreadRecordCompanion companion, CharacterFace face) {
		CharacterFace resolved = face == null ? CharacterFace.NONE : face;
		return new CompanionResponse(companion.getMember().getId(), companion.getMember().getNickname(),
			resolved.characterCode(), resolved.characterImageKey());
	}
}
