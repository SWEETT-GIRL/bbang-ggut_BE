package sweetgirl.croissant.breadlog.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 식감 칩(10/1 확정, prod 블로그 리뷰 12.7k + 빵로그 메모 881건 단어 빈도순). 순서가 곧 기본 노출 순서다.
 * 시원·사각사각은 빙수 전용이라 맨 뒤 — 빙수·아이스 그룹에서만 앞으로 온다({@link BreadGroup}).
 */
@Getter
@RequiredArgsConstructor
public enum TextureChip {
	바삭("바삭"),
	촉촉("촉촉"),
	부드러움("부드러움"),
	쫀득("쫀득"),
	쫄깃("쫄깃"),
	폭신("폭신"),
	겉바속촉("겉바속촉"),
	꾸덕("꾸덕"),
	가벼움("가벼움"),
	묵직("묵직"),
	겹겹("겹겹"),
	사르르("사르르"),
	포슬포슬("포슬포슬"),
	속꽉참("속 꽉참"),
	시원("시원"),
	사각사각("사각사각");

	private final String label;
}
