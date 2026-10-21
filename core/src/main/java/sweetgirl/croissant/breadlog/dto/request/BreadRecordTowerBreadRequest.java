package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.NotBlank;

public record BreadRecordTowerBreadRequest(@NotBlank String towerBread) {
}
