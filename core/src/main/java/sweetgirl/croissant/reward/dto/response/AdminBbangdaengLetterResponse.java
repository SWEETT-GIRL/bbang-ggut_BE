package sweetgirl.croissant.reward.dto.response;

import sweetgirl.croissant.reward.entity.BbangdaengLetter;

public record AdminBbangdaengLetterResponse(Long id, int seq, String body, boolean active) {

	public static AdminBbangdaengLetterResponse from(BbangdaengLetter letter) {
		return new AdminBbangdaengLetterResponse(letter.getId(), letter.getSeq(), letter.getBody(), letter.isActive());
	}
}
