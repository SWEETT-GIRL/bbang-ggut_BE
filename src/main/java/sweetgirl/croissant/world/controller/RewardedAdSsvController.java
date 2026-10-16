package sweetgirl.croissant.world.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.world.service.RewardedAdSsvService;

/**
 * AdMob 서버 보상 검증 콜백. 구글이 부르는 자리라 인증이 없고, 서명으로 진짜인지를 가린다.
 *
 * <p><b>무슨 일이 있어도 200 을 돌려준다.</b> 4xx · 5xx 를 주면 구글이 같은 콜백을 계속 재시도한다.
 * 받아서 처리했는지는 로그로 남긴다.
 *
 * <p>GET 인 이유: 구글 규격이 GET 이다. 설계 문서에는 POST 로 적혀 있었다.
 */
@Slf4j
@Tag(name = "[Callback] AdMob 보상 검증", description = "구글이 부르는 서버 보상 검증 콜백")
@RestController
@RequiredArgsConstructor
@RequestMapping("/ads/rewarded")
public class RewardedAdSsvController {

	private final RewardedAdSsvService rewardedAdSsvService;

	@Operation(summary = "보상 검증 콜백", description = "서명을 확인한 뒤 광고 한 편을 적립한다. 누구의 어떤 친구인지는 custom_data "
		+ "(memberId:characterCode)로 온다. nickname:·crumbs:·feed:(밥 룰렛 한 판 더)로 시작하면 그 용도로 간다. "
		+ "앱이 보내는 요청은 적립하지 않는다.")
	@GetMapping("/ssv")
	public ResponseEntity<Void> verify(HttpServletRequest request) {
		try {
			rewardedAdSsvService.credit(request.getQueryString());
		} catch (RuntimeException exception) {
			// 여기서 4xx · 5xx 가 나가면 구글이 같은 콜백을 끝없이 재시도한다. 그 편이 못 받은 것보다 나쁘다 —
			// 재시도가 쌓이는 동안 진짜 콜백까지 밀린다. 못 받은 보상은 로그를 보고 어드민으로 지급한다.
			log.error("[AdMob SSV] 처리하다 실패했다 - query={}", request.getQueryString(), exception);
		}
		return ResponseEntity.ok().build();
	}
}
