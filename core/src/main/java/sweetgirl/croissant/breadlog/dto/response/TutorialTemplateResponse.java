package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.breadlog.dto.TutorialTemplateContent;

public record TutorialTemplateResponse(String code, boolean published, LocalDateTime createdAt,
		TutorialTemplateContent content) {
}
