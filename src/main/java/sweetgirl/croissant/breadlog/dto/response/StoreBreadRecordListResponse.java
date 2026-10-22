package sweetgirl.croissant.breadlog.dto.response;

import java.util.List;

/** 가게 공개 빵로그 목록 — {@code SliceResponse} 모양에 전체 수를 더한 것(10/22, 머리 "빵로그 12 · 추천순"). */
public record StoreBreadRecordListResponse(
		List<StoreBreadRecordResponse> content,
		boolean hasNext,
		long totalCount) {
}
