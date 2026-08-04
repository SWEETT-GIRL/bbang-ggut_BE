package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDateTime;

public record BreadRecordCreateResponse(Long recordId, LocalDateTime createdAt) {
}
