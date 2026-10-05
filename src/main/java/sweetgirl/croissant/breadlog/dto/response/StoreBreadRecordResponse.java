package sweetgirl.croissant.breadlog.dto.response;

import java.time.LocalDate;
import java.util.List;

import sweetgirl.croissant.breadlog.entity.BreadRecord;

/**
 * 가게 상세에 쌓이는 남의 공개 빵로그. 누가 언제 어디 있었는지 드러나지 않게 먹은 시각은 날짜까지만,
 * 지역 키는 내려주지 않는다.
 */
public record StoreBreadRecordResponse(
		Long id,
		Long authorId,
		String authorNickname,
		String memo,
		List<String> lovedBreads,
		LocalDate eatenOn,
		List<BreadRecordPhotoResponse> photos,
		List<BreadRecordTagResponse> tags) {

	public static StoreBreadRecordResponse of(
			BreadRecord record,
			List<BreadRecordPhotoResponse> photos,
			List<BreadRecordTagResponse> tags) {
		return new StoreBreadRecordResponse(
			record.getId(),
			record.getMember().getId(),
			record.getMember().getNickname(),
			record.getMemo(),
			BreadRecordResponse.lovedBreadsOf(tags),
			record.getEatenAt().toLocalDate(),
			photos,
			tags);
	}
}
