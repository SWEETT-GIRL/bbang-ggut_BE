package sweetgirl.croissant.personalization.dto.response;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.BbtiType;

/** 빵BTI 검사 결과. 타입 카드는 아직 없어 라벨까지만 준다(09-13). */
public record BbtiResultResponse(
		@Schema(description = "4글자 타입 코드", example = "PCLN") String typeCode,
		@Schema(description = "타입 이름", example = "뉴웨이브 미니멀") String typeLabel,
		@Schema(description = "축 순서대로 −1~+1: 맛 · 식감 · 속 · 신상") List<BigDecimal> axis,
		@Schema(description = "4문항 0.7, 8문항 0.9") double confidence) {

	public static BbtiResultResponse of(BbtiType type, TasteAxis axis, double confidence) {
		return new BbtiResultResponse(
			type.code(),
			type.getLabel(),
			List.of(axis.getTaste(), axis.getTexture(), axis.getFilling(), axis.getVibe()),
			confidence);
	}
}
