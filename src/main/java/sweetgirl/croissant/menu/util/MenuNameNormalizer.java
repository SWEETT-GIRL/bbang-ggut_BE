package sweetgirl.croissant.menu.util;

import java.util.Locale;
import java.util.regex.Pattern;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 메뉴 이름 매칭 키 — 운영 유니크 인덱스 uq_menu_store_normalized_name 의 식
 * {@code lower(regexp_replace(name, '[^[:alnum:]]+', '', 'g'))} 과 같은 결과여야 한다.
 * 이 키로 중복 조회(MenuCommandService·AdminMenuCommandService)를 하는데, 인덱스와 기준이 다르면
 * 조회는 "새 메뉴"라 하고 INSERT 는 인덱스에 막혀 500 이 난다.
 * 화면에 보이는 name 은 손대지 않는다 — 여기서 지우는 건 비교용 키에서만이다.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MenuNameNormalizer {

	/** 한글·영문·숫자만 남기고 나머지(공백·기호·괄호 등) 제거 */
	private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

	public static String normalize(String name) {
		if (name == null) {
			return "";
		}
		return NON_ALPHANUMERIC.matcher(name).replaceAll("").toLowerCase(Locale.ROOT);
	}
}
