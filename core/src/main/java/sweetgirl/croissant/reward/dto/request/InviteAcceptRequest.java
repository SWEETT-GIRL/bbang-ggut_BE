package sweetgirl.croissant.reward.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record InviteAcceptRequest(@NotBlank @Size(max = 8) String code) {
}
