package sweetgirl.croissant.profile.dto.response;

/** 빵로그 상세 · 목록 줄에 붙는 글쓴이 한 줄 — 얼굴 · 닉네임 · "빵로그 12개 · 성수동 빵순이" · 팔로우 상태. */
public record AuthorCardResponse(
		Long memberId,
		String nickname,
		String characterCode,
		String characterImageKey,
		long publicRecordCount,
		String title,
		boolean isMe,
		boolean followedByMe,
		boolean followsMe) {
}
