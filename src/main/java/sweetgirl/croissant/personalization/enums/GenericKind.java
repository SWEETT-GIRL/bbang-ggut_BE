package sweetgirl.croissant.personalization.enums;

import java.util.Arrays;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 빵 종류로 치지 않는 메뉴 카테고리 이름 — 취향 재료에서 뺀다.
 *
 * <p>"빵" · "기타" 는 분류가 안 된 메뉴가 떨어지는 자리라 회원의 취향을 말해 주지 않는다. 자주 먹는 종류에 들어가면
 * 이유 문장에 "자주 드시는 기타" 가 나오고, 종류 겹침 점수는 "빵" 이 붙은 가게면 다 맞는다 — 빵집 37,530곳 중
 * {@code kinds} 에 "빵" 이 있는 곳이 47%, "기타" 가 35% 다(2026-09-19 랩). 요거트는 빵집 메뉴가 아니다.
 *
 * <p>셋은 새로 고른 게 아니라 팀이 이미 정해 둔 셋이다 — 메뉴 카테고리 축 시드 규약이 "빵·기타·요거트는 축 없음" 이다
 * ({@code V20260909110000}). 그 시드는 속 채점 오류로 되돌렸지만({@code V20260909140000}) 되돌린 건 값이고, 이 셋을 축
 * 밖에 두는 판단은 그대로다.
 */
@Getter
@RequiredArgsConstructor
public enum GenericKind {
	BREAD("빵"),
	ETC("기타"),
	YOGURT("요거트");

	private final String name;

	public static boolean isGeneric(String kind) {
		return kind != null && Arrays.stream(values()).anyMatch(generic -> generic.name.equals(kind));
	}
}
