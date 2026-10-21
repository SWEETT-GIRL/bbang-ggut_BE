package sweetgirl.croissant.breadlog.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 맛 칩(10/1 확정). 순서가 곧 기본 노출 순서다. */
@Getter
@RequiredArgsConstructor
public enum TasteChip {
	고소("고소"),
	달달("달달"),
	당충전("당충전"),
	단짠("단짠"),
	버터향("버터향"),
	진함("진함"),
	상큼("상큼"),
	담백("담백"),
	짭짤("짭짤"),
	안느끼함("안 느끼함");

	private final String label;
}
