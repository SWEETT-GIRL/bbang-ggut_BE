package sweetgirl.croissant.moderation.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ForbiddenWordKind {

	BLOCK("막는 말"),
	ALLOW("막는 말을 품었지만 괜찮은 말");

	private final String description;
}
