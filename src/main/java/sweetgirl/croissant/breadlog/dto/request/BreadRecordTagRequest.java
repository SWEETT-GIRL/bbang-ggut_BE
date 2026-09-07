package sweetgirl.croissant.breadlog.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BreadRecordTagRequest(
		@Min(0) int photoSortOrder,
		@NotBlank @Size(max = 100) String name,
		@JsonProperty("x") @DecimalMin("0.0") @DecimalMax("1.0") double positionX,
		@JsonProperty("y") @DecimalMin("0.0") @DecimalMax("1.0") double positionY,
		/** 이름표 꾸밈("모양.종이[.up]"). 구버전 앱은 안 보낸다 */
		@Size(max = 50) String style) {
}
