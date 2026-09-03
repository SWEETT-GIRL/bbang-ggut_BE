package sweetgirl.croissant.letter.dto.response;

import java.util.List;

public record AdminLetterThreadListResponse(
		List<AdminLetterThreadResponse> threads,
		boolean hasNext) {
}
