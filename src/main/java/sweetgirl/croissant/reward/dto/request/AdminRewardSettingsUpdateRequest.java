package sweetgirl.croissant.reward.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/** 보상 수치 변경. 보낸 키만 바뀐다. */
public record AdminRewardSettingsUpdateRequest(
		@NotEmpty @Valid List<Entry> settings) {

	public record Entry(
			@NotBlank String key,
			@NotNull @Min(0) Integer value) {
	}
}
