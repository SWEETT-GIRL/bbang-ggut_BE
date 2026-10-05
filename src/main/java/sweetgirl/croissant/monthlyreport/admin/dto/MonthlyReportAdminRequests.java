package sweetgirl.croissant.monthlyreport.admin.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportAsset.Kind;

public final class MonthlyReportAdminRequests {
	private MonthlyReportAdminRequests() {
		// 요청 DTO만 제공한다.
	}

	public record CreateRun(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,59}") String assetVersion) {
	}
	/** null이면 알림함만 적재하고 푸시는 예약하지 않는다. */
	public record Publish(Instant pushAt) {
	}
	public record Asset(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,59}") String version,
			@NotNull Kind kind, @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,29}") String characterCode,
			@NotNull @Pattern(regexp = "[a-z0-9-]{0,30}") String planetCode,
			@NotBlank @Pattern(regexp = "[a-z0-9]{1,10}") String variant,
			@Pattern(regexp = "image/png|image/webp") @NotNull String contentType,
			@Size(max = 500) String imageKey) {
	}
}
