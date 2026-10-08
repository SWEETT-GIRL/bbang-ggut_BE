package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

public record TutorialRecordStateResponse(
		boolean enabled, String templateCode, String photoPath, String storeName,
		String breadName, List<String> towerBreads,
		boolean completed, Long recordId, boolean recordDeleted) {
}
