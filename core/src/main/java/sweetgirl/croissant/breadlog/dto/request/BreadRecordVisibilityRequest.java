package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.NotNull;

public record BreadRecordVisibilityRequest(@NotNull Boolean isPublic) {
}
