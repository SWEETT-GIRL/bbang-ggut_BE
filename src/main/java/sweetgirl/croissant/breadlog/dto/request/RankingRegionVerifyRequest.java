package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * 지금 있는 곳의 좌표 — 서버가 시·군·구로 바꿔 저장한다. 한국 범위 밖이면 400.
 *
 * @param latitude  위도(33~39)
 * @param longitude 경도(124~132)
 */
public record RankingRegionVerifyRequest(
		@NotNull @DecimalMin("33.0") @DecimalMax("39.0") Double latitude,
		@NotNull @DecimalMin("124.0") @DecimalMax("132.0") Double longitude) {
}
