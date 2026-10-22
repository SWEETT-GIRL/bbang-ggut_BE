package sweetgirl.croissant.profile.dto.response;

/** 사람 줄에 붙는 얼굴 — 지금 데리고 다니는 캐릭터 코드와 front 이미지 키. 세계관이 없으면 마스코트. */
public record CharacterFace(String characterCode, String characterImageKey) {

	public static final CharacterFace NONE = new CharacterFace(null, null);
}
