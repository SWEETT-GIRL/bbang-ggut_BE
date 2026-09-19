package sweetgirl.croissant.personalization.dto.response;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.BbtiType;

/** 빵BTI 검사 결과. 유형 이름은 주지 않는다 — 앱에서 없앴다(#760). */
public record BbtiResultResponse(
		@Schema(description = "4글자 타입 코드", example = "PCLN") String typeCode,
		@Schema(description = "축 순서대로 −1~+1: 맛 · 식감 · 속 · 신상") List<BigDecimal> axis,
		@Schema(description = "4문항 0.7, 8문항 0.9") double confidence) {

	public static BbtiResultResponse of(BbtiType type, TasteAxis axis, double confidence) {
		return new BbtiResultResponse(
			type.code(),
			List.of(axis.getTaste(), axis.getTexture(), axis.getFilling(), axis.getVibe()),
			confidence);
	}
}
