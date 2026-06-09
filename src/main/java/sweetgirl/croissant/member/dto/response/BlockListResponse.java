package sweetgirl.croissant.member.dto.response;

import java.util.List;

public record BlockListResponse(
		List<BlockResponse> content,
		boolean hasNext) {
}
