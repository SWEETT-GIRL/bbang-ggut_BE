package sweetgirl.croissant.pat.dto.response;

import java.time.LocalDate;
import java.util.List;

import sweetgirl.croissant.pat.entity.MemberPat;
import sweetgirl.croissant.profile.dto.response.CharacterFace;

/** 나에게 톡 친 사람들. {@code today} 는 오늘 받은 게 없으면 null — 홈 한 줄을 그릴지 말지는 이것으로 본다. */
public record PatListResponse(
		TodaySummary today,
		List<PatResponse> items) {

	public static PatListResponse of(LocalDate today, List<PatResponse> items) {
		List<PatResponse> todayItems = items.stream().filter(item -> today.equals(item.date())).toList();
		TodaySummary summary = todayItems.isEmpty() ? null
			: new TodaySummary(todayItems.get(0).memberId(), todayItems.get(0).nickname(), todayItems.size());
		return new PatListResponse(summary, items);
	}

	/** "오늘 {firstNickname}님 외 {count - 1}명이 톡 쳤어요". {@code count} 는 오늘 친 사람 수(첫 사람 포함). */
	public record TodaySummary(Long firstMemberId, String firstNickname, int count) {
	}

	public record PatResponse(
			LocalDate date,
			Long memberId,
			String nickname,
			String characterCode,
			String characterImageKey) {
		public static PatResponse of(MemberPat pat, CharacterFace face) {
			CharacterFace resolved = face == null ? CharacterFace.NONE : face;
			return new PatResponse(pat.getId().getKstDate(), pat.getFromMember().getId(),
				pat.getFromMember().getNickname(), resolved.characterCode(), resolved.characterImageKey());
		}
	}
}
