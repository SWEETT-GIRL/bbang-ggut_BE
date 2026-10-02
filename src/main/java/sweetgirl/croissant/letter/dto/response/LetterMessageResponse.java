package sweetgirl.croissant.letter.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import sweetgirl.croissant.letter.entity.LetterMessage;
import sweetgirl.croissant.letter.enums.LetterCategory;
import sweetgirl.croissant.letter.enums.LetterSender;

/** 편지 한 통. sender로 말풍선 방향이 정해진다. */
public record LetterMessageResponse(
		Long messageId,
		LetterSender sender,
		String body,
		/** 첨부 사진의 S3 키들. 없으면 빈 목록. */
		List<String> imageKeys,
		LetterCategory category,
		boolean read,
		LocalDateTime sentAt) {

	public static LetterMessageResponse from(LetterMessage message) {
		return new LetterMessageResponse(
			message.getId(),
			message.getSender(),
			message.getBody(),
			List.copyOf(message.getImageKeys()),
			message.getCategory(),
			message.isRead(),
			message.getCreatedAt());
	}
}
