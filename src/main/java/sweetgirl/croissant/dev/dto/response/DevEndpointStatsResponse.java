package sweetgirl.croissant.dev.dto.response;

/** 엔드포인트 하나의 응답시간 통계. 백분위는 nearest-rank(정렬 뒤 ceil(p·n) 번째 값)라 실제 관측값이다. */
public record DevEndpointStatsResponse(
		String endpoint,
		int count,
		int p50Ms,
		int p95Ms,
		int p99Ms,
		int maxMs,
		int slowCount,
		int serverErrorCount) {
}
