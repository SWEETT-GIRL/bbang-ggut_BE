package sweetgirl.dataops.snapshot.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.snapshot.code.SnapshotErrorCode;
import sweetgirl.dataops.snapshot.exception.SnapshotException;
import sweetgirl.dataops.snapshot.parser.NaverPlaceParser;
import sweetgirl.dataops.snapshot.parser.ParsedMenu;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 변경 감지용 정규화(canonical) 해시 — 원본 전체가 아니라 의미 필드만 해시한다.
 *
 * 원본 전체 해시는 재방문의 97.6%를 "변경"으로 오판했다 (2회차 스윕 실측 — 실제 메뉴 변경은 표본의 3%).
 * 원인: 당일 영업상태, 영업시간 배열의 "오늘 요일부터" 회전, 임시휴무 주간의 날짜 라벨("토(8/1)"),
 * 메뉴 엔티티 키 순서 회전, 길찾기·사진·리뷰 등 UI성 휘발 데이터.
 * → 정규화 규칙: 당일 상태 제외 · 영업시간 요일 정렬 + 날짜 라벨 제거 · 메뉴 내용 기준 정렬.
 *
 * 해시 세대 (v3 P2-1): 입력 목록은 재판정(ReconcileService)이 실제로 소비하는 필드와 일치해야 한다.
 * 1세대는 주소·좌표를 빼먹어서, 이름·전화·메뉴·영업시간이 그대로인 채 이전(移轉)한 가게는 해시가 같아
 * 스냅샷이 저장되지 않았고 재판정도 돌지 않았다 — 이전 자체가 파이프라인에 보이지 않았다.
 * 2세대가 주소·좌표를 포함한다. 세대 전환을 가짜 "변경"으로 만들지 않기 위해 1세대 해시 계산을 남겨 두고,
 * 저장된 해시의 세대에 맞춰 비교한다 ({@link #hashFor}).
 *
 * 3세대 (2026-09-27): 영업시간을 뺀다. 날짜 라벨을 떼도 임시 일정("토(9/26)" 휴무)이 그 요일의 정기 값 자리에 들어가,
 * 매일 방문의 약 95%가 가짜 변경이었다(표본 2차 61곳 중 58곳). 영업시간 변경은 {@link RegularHoursTracker} 가
 * 요일별 정기 값 기억과 공통 요일끼리 비교해 판정한다 — docs/hours-change-detection-design.md.
 */
@Service
@RequiredArgsConstructor
public class ContentHashService {

	private static final String DATE_LABEL_PATTERN = "\\(\\d{1,2}/\\d{1,2}\\)";

	/** 현재 해시 세대 — 입력 목록을 바꾸면 올린다. 올리면 저장된 옛 세대 해시와 비교하지 않게 된다 */
	public static final short VERSION = 3;
	public static final short SCOPE_VERSION = 1;
	private static final short VERSION_WITHOUT_LOCATION = 1;
	/** 영업시간을 해시에 넣던 마지막 세대 — 3세대부터는 RegularHoursTracker 가 따로 본다 */
	private static final short LAST_VERSION_WITH_HOURS = 2;

	/**
	 * 좌표 비교의 자리수 — 소수 6자리(약 11cm). 네이버가 같은 자리를 다른 정밀도로 보내는 경우만 흡수하고
	 * 실제 이전은 놓치지 않는다. 150m 임계값 판정은 재판정이 하고, 해시는 "달라졌는지"만 본다.
	 */
	private static final int COORDINATE_SCALE = 6;

	private final NaverPlaceParser naverPlaceParser;
	private final ObjectMapper objectMapper;

	public String canonicalHash(JsonNode payload) {
		return canonicalHash(ResponseDiagnostics.parsePreservingInput(naverPlaceParser, payload));
	}

	public String canonicalHash(ParsedPlace parsedPlace) {
		return hashFor(parsedPlace, VERSION);
	}

	/** Independent baselines prevent partial menus from replacing the last complete menu comparison. */
	public String nonMenuHash(ParsedPlace place) {
		return canonicalHash(new ParsedPlace(place.name(), place.phone(), place.address(), place.roadAddress(),
			place.longitude(), place.latitude(), place.businessStatusDescription(), null, place.instagramHandle(),
			List.of(), List.of()));
	}

	public String menuHash(ParsedPlace place) {
		return sha256Hex(objectMapper.writeValueAsString(List.of(canonicalMenus(place.menus()),
			canonicalMenus(place.baeminMenus()))));
	}

	/** Baemin observations cannot advance the standard-menu completeness baseline. */
	public String baeminMenuHash(ParsedPlace place) {
		List<String> menus = place.baeminMenus().stream()
			.map(menu -> objectMapper.writeValueAsString(List.of(
				nullSafe(menu.name()), nullSafe(menu.price()), nullSafe(menu.description()),
				nullSafe(menu.groupName()), menu.imageUrls())))
			.sorted().toList();
		return sha256Hex("baemin-menu-v2\n" + objectMapper.writeValueAsString(menus));
	}

	/**
	 * 저장된 해시의 세대에 맞춰 계산한다 — 세대가 올라간 직후의 재방문을 전부 "변경"으로 만들지 않기 위해서다.
	 * 알 수 없는 세대는 현재 세대로 계산한다(비교가 달라져 스냅샷이 저장되는 쪽 — 놓치지 않는 쪽으로 기운다).
	 */
	public String hashFor(ParsedPlace parsedPlace, short version) {
		Map<String, Object> canonical = new LinkedHashMap<>();
		canonical.put("name", parsedPlace.name());
		canonical.put("phone", parsedPlace.phone());
		if (version <= LAST_VERSION_WITH_HOURS) {
			// 옛 세대 해시와 비교할 때만 — 키 순서까지 그 세대와 같아야 같은 해시가 나온다
			canonical.put("businessHours", canonicalHours(parsedPlace.businessHoursRaw()));
		}
		canonical.put("instagram", parsedPlace.instagramHandle());
		canonical.put("menus", canonicalMenus(parsedPlace.menus()));
		canonical.put("baeminMenus", canonicalMenus(parsedPlace.baeminMenus()));
		if (version != VERSION_WITHOUT_LOCATION) {
			// 재판정이 주소·좌표를 소비한다 — 해시에 없으면 이전(移轉)이 판정층에 도달하지 못한다
			canonical.put("address", parsedPlace.address());
			canonical.put("roadAddress", parsedPlace.roadAddress());
			canonical.put("longitude", canonicalCoordinate(parsedPlace.longitude()));
			canonical.put("latitude", canonicalCoordinate(parsedPlace.latitude()));
		}
		return sha256Hex(objectMapper.writeValueAsString(canonical));
	}

	/** 좌표를 고정 자리수 문자열로 — double 의 표현 차이(1.0 vs 1.00)가 해시를 흔들지 않게 한다 */
	private String canonicalCoordinate(Double value) {
		return value == null ? "" : java.math.BigDecimal.valueOf(value)
			.setScale(COORDINATE_SCALE, java.math.RoundingMode.HALF_UP).toPlainString();
	}

	/** 요일별 항목을 "요일|시작~종료|설명" 문자열로 정규화해 정렬 — 배열 회전·날짜 라벨에 불변 */
	private List<String> canonicalHours(String businessHoursRaw) {
		if (businessHoursRaw == null) {
			return List.of();
		}
		JsonNode hours = objectMapper.readTree(businessHoursRaw);
		List<String> normalized = new ArrayList<>();
		if (hours.isArray()) {
			hours.forEach(day -> {
				String dayName = day.path("day").asString("").replaceAll(DATE_LABEL_PATTERN, "").strip();
				JsonNode window = day.path("businessHours");
				String description = day.path("description").asString("");
				normalized.add(dayName + "|" + window.path("start").asString("") + "~"
					+ window.path("end").asString("") + "|" + description);
			});
		}
		normalized.sort(Comparator.naturalOrder());
		return normalized;
	}

	private List<String> canonicalMenus(List<ParsedMenu> menus) {
		return menus.stream()
			.map(menu -> String.join("|",
				nullSafe(menu.name()), nullSafe(menu.price()), nullSafe(menu.description()),
				nullSafe(menu.groupName()), String.join(",", menu.imageUrls())))
			.sorted()
			.toList();
	}

	private String nullSafe(String value) {
		return value != null ? value : "";
	}

	private String sha256Hex(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new SnapshotException(SnapshotErrorCode.SNAPSHOT_HASH_FAILED, e.getMessage());
		}
	}
}
