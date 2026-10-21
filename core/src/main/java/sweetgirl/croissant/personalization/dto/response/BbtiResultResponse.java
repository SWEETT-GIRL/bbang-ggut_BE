package sweetgirl.croissant.personalization.dto.response;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import sweetgirl.croissant.personalization.entity.TasteAxis;
import sweetgirl.croissant.personalization.enums.BbtiType;

/** 빵BTI 검사 결과. 유형 이름은 주지 않는다 — 앱에서 없앴다(#760). */
public record BbtiResultResponse(
		@Schema(description = "4글자 타입 코드 — 48종. 1 맛 S달콤/P담백/T짭짤/M풍미 · 2 식감 F부드/C바삭/Y쫄깃 "
			+ "· 3 속 R꽉찬/L심플 · 4 스타일 O클래식/N트렌디", example = "TYLN") String typeCode,
		@Schema(description = "축 순서대로 −1~+1: 맛(달콤↔담백) · 식감(폭신↔바삭) · 속(꽉 찬↔심플) · 빵 스타일(클래식↔트렌디). "
			+ "갈래가 넷·셋인 맛과 식감은 여기서 정보가 줄어든다(짭짤은 담백과 같은 +1, 풍미·쫄깃은 0) — "
			+ "정확한 값은 labels 를 본다") List<BigDecimal> axis,
		@Schema(description = "검사가 드러낸 취향 라벨 9개(0~1). 검사를 푼 회원만 갖는다") TasteLabels labels,
		@Schema(description = "4문항 0.7, 8문항 0.9") double confidence) {

	public static BbtiResultResponse of(BbtiType type, TasteAxis axis, TasteLabels labels, double confidence) {
		return new BbtiResultResponse(
			type.code(),
			List.of(axis.getTaste(), axis.getTexture(), axis.getFilling(), axis.getVibe()),
			labels,
			confidence);
	}
}
