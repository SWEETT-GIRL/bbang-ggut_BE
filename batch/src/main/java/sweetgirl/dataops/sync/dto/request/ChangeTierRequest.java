package sweetgirl.dataops.sync.dto.request;

import jakarta.validation.constraints.NotNull;
import sweetgirl.dataops.sync.enums.SyncTier;

public record ChangeTierRequest(@NotNull SyncTier tier) {
}
