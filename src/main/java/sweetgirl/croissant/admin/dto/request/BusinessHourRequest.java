package sweetgirl.croissant.admin.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "요일별 영업시간")
public record BusinessHourRequest(
		@Schema(description = "오픈 시 (0~23)", example = "9") int openHour,
		@Schema(description = "오픈 분 (0~59)", example = "0") int openMin,
		@Schema(description = "마감 시 (0~23)", example = "21") int closeHour,
		@Schema(description = "마감 분 (0~59)", example = "0") int closeMin,
		@Schema(description = "휴무 여부 (true면 해당 요일 휴무)") boolean closed) {
}
