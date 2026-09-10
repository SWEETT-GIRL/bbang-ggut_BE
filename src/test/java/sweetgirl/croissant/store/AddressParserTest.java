package sweetgirl.croissant.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AddressParserTest {

	@Test
	@DisplayName("지번 주소는 시·도·구·동을 그대로 가른다")
	void parse_lotAddress() {
		assertThat(AddressParser.parse("서울 종로구 계동길 5 카페 어니언"))
			.containsExactly("서울", "종로구", null);
		assertThat(AddressParser.parse("서울 성동구 성수동2가 315-13"))
			.containsExactly("서울", "성동구", "성수동2가");
	}

	@Test
	@DisplayName("시·도는 짧은 이름으로 접는다 — 같은 서울이 두 갈래로 저장되면 한쪽이 검색에서 통째로 빠진다")
	void parse_shortensSido() {
		assertThat(AddressParser.parse("서울특별시 성동구 성수동1가 1")[0]).isEqualTo("서울");
		assertThat(AddressParser.parse("경상북도 포항시 북구 해안로 191-1")[0]).isEqualTo("경북");
		assertThat(AddressParser.parse("강원특별자치도 강릉시 임영로155번길 11-2")[0]).isEqualTo("강원");
		assertThat(AddressParser.parse("세종특별자치시 도움3로 105-2")[0]).isEqualTo("세종");
	}

	@Test
	@DisplayName("도로명이 동 자리에 앉지 않는다 — '아차산로9길'이 동이 되면 '성수' 검색에 안 걸린다")
	void parse_doesNotPutRoadNameInDong() {
		assertThat(AddressParser.parse("서울특별시 성동구 아차산로9길 8 어니언"))
			.containsExactly("서울", "성동구", null);
		assertThat(AddressParser.parse("서울특별시 마포구 동교로34길 21"))
			.containsExactly("서울", "마포구", null);
		assertThat(AddressParser.parse("서울특별시 중구 남대문로 81"))
			.containsExactly("서울", "중구", null);
	}

	@Test
	@DisplayName("빈 값·짧은 주소도 터지지 않는다")
	void parse_handlesShortInput() {
		assertThat(AddressParser.parse(null)).containsExactly(null, null, null);
		assertThat(AddressParser.parse("  ")).containsExactly(null, null, null);
		assertThat(AddressParser.parse("서울")).containsExactly("서울", null, null);
		assertThat(AddressParser.parse("서울 강남구")).containsExactly("서울", "강남구", null);
	}
}
