package sweetgirl.croissant.admin.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.external.llm.LlmProvider;
import sweetgirl.croissant.external.llm.client.LlmClientRegistry;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.recommendation.dto.request.ReasonExperimentRequest;
import sweetgirl.croissant.recommendation.dto.response.ReasonExperimentResponse;
import sweetgirl.croissant.recommendation.service.ReasonExperimentService;

/**
 * 이유 문장 실험 (#662). 룰이 고른 같은 4곳에 벤더 × 모델 × 프롬프트 변형으로 문장을 받아 지연·토큰·문장을 나란히 본다.
 *
 * <p>호출마다 실제 비용이 나가고 결과는 저장되지 않는다. 열쇠({@code X-Admin-Key})가 필요하다.
 */
@RestController
@RequestMapping("/admin/recommendations")
@RequiredArgsConstructor
@Tag(name = "Admin Recommendation Experiment", description = "추천 이유 문장 LLM 실험")
public class AdminReasonExperimentController {

	private final ReasonExperimentService reasonExperimentService;
	private final LlmClientRegistry llmClientRegistry;

	@PostMapping("/reason-experiment")
	@Operation(summary = "이유 문장 실험 실행", description = "회원 취향과 상권으로 추천 후보를 뽑아(저장하지 않는다) "
		+ "모델 × 프롬프트 변형마다 이유 문장을 받는다. models 는 '벤더:모델', 비면 설정값. blind=true 면 룰 문구까지 "
		+ "섞어 라벨만 붙여 돌려준다.")
	public ApiResponse<ReasonExperimentResponse> runExperiment(
			@Valid @RequestBody ReasonExperimentRequest request) {
		return ApiResponse.ok(reasonExperimentService.run(request));
	}

	@GetMapping("/reason-experiment/providers")
	@Operation(summary = "키가 설정된 벤더 목록", description = "실험을 돌리기 전에 어떤 벤더 키가 들어와 있는지 확인한다. "
		+ "키 값은 나가지 않는다.")
	public ApiResponse<List<LlmProvider>> configuredProviders() {
		return ApiResponse.ok(llmClientRegistry.configuredProviders());
	}
}
