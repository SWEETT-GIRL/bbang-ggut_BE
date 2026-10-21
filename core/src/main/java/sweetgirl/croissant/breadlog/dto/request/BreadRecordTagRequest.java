package sweetgirl.croissant.breadlog.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BreadRecordTagRequest(
		/** 이 빵이 찍힌 사진의 순서. 사진에 안 붙은 빵 이름이면 안 보낸다(그때는 x · y 도 안 보낸다). */
		@Min(0) Integer photoSortOrder,
		@NotBlank @Size(max = 100) String name,
		/**
		 * 앱이 고른 가게 메뉴. 안 보내면 서버가 이름으로 찾는다 — 구버전 앱과 가게를 안 고른 기록이 그렇다.
		 * 이 가게 메뉴가 아니면(지워졌거나 다른 가게) 무시하고 이름 매칭으로 물러선다.
		 */
		Long menuId,
		/**
		 * 이 자리에 AI 가 제안했던 메뉴. 제안을 그대로 쓴 태그든 유저가 다른 걸로 고친 태그든,
		 * "AI 가 뭘 제안했었나"를 남기는 게 목적이다. 안 보내면 null — 직접 단 태그와 구버전 앱이 그렇다.
		 */
		Long suggestedMenuId,
		@JsonProperty("x") @DecimalMin("0.0") @DecimalMax("1.0") Double positionX,
		@JsonProperty("y") @DecimalMin("0.0") @DecimalMax("1.0") Double positionY,
		/** 이름표 꾸밈("모양.종이[.up]"). 구버전 앱은 안 보낸다 */
		@Size(max = 50) String style,
		/**
		 * 빵 평가. 생성 때 없으면 평가 없음, 수정 때 없으면(구버전 앱) 이름 같은 이전 태그의 평가를 이어간다.
		 * 빈 평가를 보내면({@code {}}) 이전 평가를 지우는 것이다.
		 */
		@Valid BreadTagRatingRequest rating) {
}
