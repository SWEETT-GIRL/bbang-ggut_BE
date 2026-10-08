package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

import sweetgirl.croissant.breadlog.dto.TutorialTemplateContent;

public record TutorialRecordStateResponse(
		boolean enabled, String templateCode, TutorialTemplateContent template, List<String> towerBreads,
		boolean completed, Long recordId, boolean recordDeleted) {
}
