package sweetgirl.croissant.external.kakao.enums;

import java.util.Arrays;

public enum FranchiseName {

	파리바게뜨,
	뚜레쥬르,
	파리크라상,
	빵장수단팥빵,
	명랑시대쌀핫도그,
	던킨,
	크리스피크림도넛,
	폴바셋,
	스타벅스,
	투썸플레이스,
	이디야,
	메가MGC커피,
	컴포즈커피,
	빽다방,
	더벤티,
	커피빈,
	할리스,
	엔제리너스,
	파스쿠찌,
	탐앤탐스,
	카페베네,
	커피베이,
	카페봄봄,
	매머드커피,
	매머드익스프레스,
	커피에반하다,
	커피나무,
	텐퍼센트커피,
	디저트39,
	요거프레소,
	공차,
	팔공티,
	아마스빈,
	타이거슈가,
	스무디킹,
	쥬씨,
	설빙,
	바나프레소,
	카페인중독,
	커피랑도서관,
	카페희다,
	블루보틀,
	아티제,
	와플대학,
	이삭토스트;

	public static boolean matches(String placeName) {
		return Arrays.stream(values()).anyMatch(f -> placeName.startsWith(f.name()));
	}
}
