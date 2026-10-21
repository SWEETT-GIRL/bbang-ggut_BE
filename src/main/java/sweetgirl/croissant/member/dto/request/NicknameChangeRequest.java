package sweetgirl.croissant.member.dto.request;

import jakarta.validation.constraints.NotBlank;

public record NicknameChangeRequest(@NotBlank String nickname) {
}
