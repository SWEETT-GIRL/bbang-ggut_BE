package sweetgirl.croissant.dev.controller;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.dev.dto.response.DevCouponResetResponse;
import sweetgirl.croissant.dev.service.DevCouponService;
import sweetgirl.croissant.global.response.ApiResponse;

/**
 * 부하 측정용 쿠폰 초기화. <b>local·staging 에서만 뜬다 — prod 에는 올라가지 않는다.</b>
 *
 * <p>측정은 회차를 여러 번 돌려야 의미가 있는데, 한 번 돌리면 재고가 소진되고 발급 내역이 쌓여
 * 다음 회차는 전부 소진 응답만 받는다. 회차 사이에 사람이 손으로 DB 를 지우면 조건이 흔들리므로
 * 측정 스크립트가 직접 부를 수 있게 엔드포인트로 둔다.
 *
 * <p>{@code DevPerfController} 와 달리 이건 <b>쓰기</b>라서 prod 프로파일을 뺐고,
 * local 밖에서는 같은 대시보드 키를 요구한다. 키는 cd-staging 이 내려준다 —
 * 비어 있으면 그 환경에서는 닫힌다.
 */
@Profile({"local", "staging"})
@Tag(name = "[Dev] 부하 측정", description = "측정 회차 사이에 쿠폰을 초기화한다 (local·staging 전용)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/dev/coupons")
public class DevCouponController {

	private final DevCouponService devCouponService;
	private final Environment environment;

	@Value("${perf.dashboard.enabled:false}")
	private boolean perfDashboardEnabled;

	@Value("${perf.dashboard.access-key:}")
	private String perfDashboardAccessKey;

	@Operation(summary = "측정용 쿠폰 초기화", description = "발급 내역을 지우고 재고를 되돌린다. 없으면 만든다. 응답의 couponId 를 k6 가 쓴다. "
		+ "local 밖에서는 X-Perf-Dashboard-Key 헤더가 필요하다.")
	@PostMapping("/reset")
	public ApiResponse<DevCouponResetResponse> resetLoadTestCoupon(
			@RequestHeader(value = "X-Perf-Dashboard-Key", required = false) String accessKey,
			@RequestParam(defaultValue = "100") int totalQuantity) {
		validateAccess(accessKey);
		return ApiResponse.ok(devCouponService.reset(totalQuantity));
	}

	/** {@code DevPerfController.validateAccess} 와 같은 규칙 — local 은 통과, 그 밖은 키를 본다. */
	private void validateAccess(String accessKey) {
		if (Arrays.asList(environment.getActiveProfiles()).contains("local")) {
			return;
		}
		if (!perfDashboardEnabled || !StringUtils.hasText(perfDashboardAccessKey)
			|| !perfDashboardAccessKey.equals(accessKey)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "performance dashboard access denied");
		}
	}
}
