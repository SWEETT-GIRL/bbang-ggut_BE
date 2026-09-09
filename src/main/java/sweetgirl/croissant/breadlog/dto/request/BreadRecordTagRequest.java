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
		/**
		 * 앱이 고른 가게 메뉴. 안 보내면 서버가 이름으로 찾는다 — 구버전 앱과 가게를 안 고른 기록이 그렇다.
		 * 이 가게 메뉴가 아니면(지워졌거나 다른 가게) 무시하고 이름 매칭으로 물러선다.
		 */
		Long menuId,
		@JsonProperty("x") @DecimalMin("0.0") @DecimalMax("1.0") double positionX,
		@JsonProperty("y") @DecimalMin("0.0") @DecimalMax("1.0") double positionY,
		/** 이름표 꾸밈("모양.종이[.up]"). 구버전 앱은 안 보낸다 */
		@Size(max = 50) String style) {
}
