package sweetgirl.croissant.common.enums;

import java.util.Arrays;
import java.util.Optional;

import lombok.Getter;

@Getter
public enum District {

	// 서울 25구
	JONGNO_GU("서울특별시", "서울", "종로구"),
	JUNG_GU("서울특별시", "서울", "중구"),
	YONGSAN_GU("서울특별시", "서울", "용산구"),
	SEONGDONG_GU("서울특별시", "서울", "성동구"),
	GWANGJIN_GU("서울특별시", "서울", "광진구"),
	DONGDAEMUN_GU("서울특별시", "서울", "동대문구"),
	JUNGNANG_GU("서울특별시", "서울", "중랑구"),
	SEONGBUK_GU("서울특별시", "서울", "성북구"),
	GANGBUK_GU("서울특별시", "서울", "강북구"),
	DOBONG_GU("서울특별시", "서울", "도봉구"),
	NOWON_GU("서울특별시", "서울", "노원구"),
	EUNPYEONG_GU("서울특별시", "서울", "은평구"),
	SEODAEMUN_GU("서울특별시", "서울", "서대문구"),
	MAPO_GU("서울특별시", "서울", "마포구"),
	YANGCHEON_GU("서울특별시", "서울", "양천구"),
	GANGSEO_GU("서울특별시", "서울", "강서구"),
	GURO_GU("서울특별시", "서울", "구로구"),
	GEUMCHEON_GU("서울특별시", "서울", "금천구"),
	YEONGDEUNGPO_GU("서울특별시", "서울", "영등포구"),
	DONGJAK_GU("서울특별시", "서울", "동작구"),
	GWANAK_GU("서울특별시", "서울", "관악구"),
	SEOCHO_GU("서울특별시", "서울", "서초구"),
	GANGNAM_GU("서울특별시", "서울", "강남구"),
	SONGPA_GU("서울특별시", "서울", "송파구"),
	GANGDONG_GU("서울특별시", "서울", "강동구"),

	// 광역시
	BUSAN("부산광역시", "부산", "부산광역시"),
	DAEGU("대구광역시", "대구", "대구광역시"),
	INCHEON("인천광역시", "인천", "인천광역시"),
	GWANGJU("광주광역시", "광주", "광주광역시"),
	DAEJEON("대전광역시", "대전", "대전광역시"),
	ULSAN("울산광역시", "울산", "울산광역시"),

	// 기타 시
	SUWON("경기도", "경기", "수원시"),
	SEONGNAM("경기도", "경기", "성남시"),
	GOYANG("경기도", "경기", "고양시"),
	JEJU("제주특별자치도", "제주", "제주도"),
	GANGNEUNG("강원도", "강원", "강릉시"),
	CHUNCHEON("강원도", "강원", "춘천시"),
	SOKCHO("강원도", "강원", "속초시"),
	YEOSU("전라남도", "전남", "여수시"),
	JEONJU("전라북도", "전북", "전주시"),
	GYEONGJU("경상북도", "경북", "경주시"),
	CHANGWON("경상남도", "경남", "창원시");

	private final String province;
	private final String provinceShort;
	private final String city;

	District(String province, String provinceShort, String city) {
		this.province = province;
		this.provinceShort = provinceShort;
		this.city = city;
	}

	public static Optional<District> fromAddress(String address) {
		if (address == null || address.isBlank()) {
			return Optional.empty();
		}

		return Arrays.stream(values())
				.filter(district -> isMatch(address, district))
				.findFirst();
	}

	private static boolean isMatch(String address, District district) {
		boolean hasCity = address.contains(district.getCity());
		boolean hasProvince = address.contains(district.getProvince())
				|| address.contains(district.getProvinceShort());

		return hasProvince && hasCity;
	}
}
