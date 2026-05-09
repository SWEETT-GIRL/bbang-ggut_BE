package sweetgirl.croissant.common.enums;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.Getter;

@Getter
public enum District {

	// 서울 25구
	JONGNO_GU("종로구"),
	JUNG_GU("중구"),
	YONGSAN_GU("용산구"),
	SEONGDONG_GU("성동구"),
	GWANGJIN_GU("광진구"),
	DONGDAEMUN_GU("동대문구"),
	JUNGNANG_GU("중랑구"),
	SEONGBUK_GU("성북구"),
	GANGBUK_GU("강북구"),
	DOBONG_GU("도봉구"),
	NOWON_GU("노원구"),
	EUNPYEONG_GU("은평구"),
	SEODAEMUN_GU("서대문구"),
	MAPO_GU("마포구"),
	YANGCHEON_GU("양천구"),
	GANGSEO_GU("강서구"),
	GURO_GU("구로구"),
	GEUMCHEON_GU("금천구"),
	YEONGDEUNGPO_GU("영등포구"),
	DONGJAK_GU("동작구"),
	GWANAK_GU("관악구"),
	SEOCHO_GU("서초구"),
	GANGNAM_GU("강남구"),
	SONGPA_GU("송파구"),
	GANGDONG_GU("강동구"),

	// 광역시
	BUSAN("부산광역시"),
	DAEGU("대구광역시"),
	INCHEON("인천광역시"),
	GWANGJU("광주광역시"),
	DAEJEON("대전광역시"),
	ULSAN("울산광역시"),

	// 기타 시
	SUWON("수원시"),
	SEONGNAM("성남시"),
	GOYANG("고양시"),
	JEJU("제주특별자치도"),
	GANGNEUNG("강릉시"),
	CHUNCHEON("춘천시"),
	SOKCHO("속초시"),
	YEOSU("여수시"),
	JEONJU("전주시"),
	GYEONGJU("경주시"),
	CHANGWON("창원시");

	private final String name;

	private static final Map<String, District> BY_NAME = Arrays.stream(values())
		.collect(Collectors.toMap(d -> d.name, d -> d));

	District(String name) {
		this.name = name;
	}

	public static Optional<District> from(String region1, String region2) {
		if (region1 == null) {
			return Optional.empty();
		}
		if (region1.contains("서울")) {
			return Optional.ofNullable(BY_NAME.get(region2));
		}
		String cityName = region2 != null && region2.contains(" ")
			? region2.split(" ")[0]
			: region2;
		return Optional.ofNullable(BY_NAME.get(region1))
			.or(() -> Optional.ofNullable(BY_NAME.get(cityName)));
	}

	public static Optional<District> fromAddress(String address) {
		if (address == null || address.isBlank()) {
			return Optional.empty();
		}
		String[] parts = address.split(" ");
		if (parts.length < 2) {
			return Optional.empty();
		}
		return from(parts[0], parts[1]);
	}
}
