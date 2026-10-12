package sweetgirl.croissant.admin.dto.response;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
public record AdminS3ReconcileResponse(
		boolean dryRun,
		@Schema(description = "S3 에서 본 원본 객체 수") int s3KeyCount,
		@Schema(description = "DB 가 아는 원본 키 수") int dbKeyCount,
		@Schema(description = "주인 없는 객체 수 (원본 + 크기 조정본, 최근 생성분 포함)") int orphanCount,
		int deletedCount,
		@Schema(description = "주인 없는 키 표본 (앞 100개)") List<String> orphanKeys,
		@Schema(description = "DB 에는 있는데 S3 에 원본이 없는 키 수") int missingCount,
		int failedMarkedCount,
		@Schema(description = "S3 에 없는 키 표본 (앞 100개)") List<String> missingKeys,
		@Schema(description = "검사한 폴더") List<String> scopes,
		@Schema(description = "이 일수보다 최근에 생긴 객체는 지우지 않는다") int minAgeDays,
		@Schema(description = "한 번에 지울 수 있는 상한. 넘으면 하나도 지우지 않는다") int maxDelete,
		@Schema(description = "S3 에서 본 크기 조정본 객체 수") int resizedKeyCount,
		@Schema(description = "주인 없는 객체의 용량 합계(바이트)") long orphanBytes,
		@Schema(description = "주인 없는 객체 중 실제 삭제 대상 수 (최근 생성분 제외)") int deletableCount,
		long deletableBytes,
		@Schema(description = "주인이 없지만 최근에 생겨 남겨 둔 객체 수") int recentSkippedCount,
		@Schema(description = "처리 중·실패 행이나 다른 테이블이 가리켜 남겨 둔 객체 수") int protectedCount,
		@Schema(description = "삭제 대상이 상한을 넘어 삭제를 하지 않았는지") boolean deleteAborted,
		@Schema(description = "숨김 대상이 상한을 넘어 FAILED 처리를 하지 않았는지") boolean failedMarkAborted,
		List<Breakdown> breakdown,
		Instant startedAt,
		long durationMs) {

	@Schema(description = "폴더·종류별 주인 없는 객체 요약")
	public record Breakdown(
			@Schema(description = "stores | menus | bread-records") String scope,
			@Schema(description = "ORIGINAL | RESIZED") String kind,
			int orphanCount,
			long orphanBytes,
			int deletableCount,
			long deletableBytes) {
	}
}
