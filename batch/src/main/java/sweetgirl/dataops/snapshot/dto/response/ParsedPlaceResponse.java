package sweetgirl.dataops.snapshot.dto.response;

import java.util.List;

import sweetgirl.dataops.snapshot.entity.PlaceSnapshot;
import sweetgirl.dataops.snapshot.parser.ParsedPlace;

/** 파싱 미리보기 — 1단계 파서 정합성 눈검사용 (파싱 결과 vs 실제 페이지 대조) */
public record ParsedPlaceResponse(
		Long snapshotId,
		Long storeId,
		String name,
		String phone,
		String businessStatusDescription,
		String businessHoursRaw,
		String instagramHandle,
		List<ParsedMenuResponse> menus,
		List<ParsedMenuResponse> baeminMenus) {

	public static ParsedPlaceResponse of(PlaceSnapshot snapshot, ParsedPlace parsedPlace) {
		return new ParsedPlaceResponse(
			snapshot.getId(),
			snapshot.getStoreId(),
			parsedPlace.name(),
			parsedPlace.phone(),
			parsedPlace.businessStatusDescription(),
			parsedPlace.businessHoursRaw(),
			parsedPlace.instagramHandle(),
			parsedPlace.menus().stream().map(ParsedMenuResponse::from).toList(),
			parsedPlace.baeminMenus().stream().map(ParsedMenuResponse::from).toList());
	}
}
