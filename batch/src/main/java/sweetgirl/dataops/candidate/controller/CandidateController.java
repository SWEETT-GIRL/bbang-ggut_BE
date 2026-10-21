package sweetgirl.dataops.candidate.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.dataops.candidate.dto.request.CandidateReactivateRequest;
import sweetgirl.dataops.candidate.dto.request.CandidateRegisterRequest;
import sweetgirl.dataops.candidate.dto.response.CandidateRegisterPreview;
import sweetgirl.dataops.candidate.dto.response.CandidateRegisterResult;
import sweetgirl.dataops.candidate.service.CandidateRegisterService;
import sweetgirl.dataops.global.response.ApiResponse;

@Tag(name = "Candidate", description = "공공데이터 신규 인허가 후보 — 4단계 신규 가게 등록")
@RestController
@RequestMapping("/api/candidates")
@RequiredArgsConstructor
public class CandidateController {

	private final CandidateRegisterService candidateRegisterService;

	@Operation(summary = "신규 후보 등록 배치", description = "MATCHED 후보를 운영 store 로 INSERT. "
		+ "placeId 선점 확인(중복 귀속) → INSERT → apply_log·provenance → sync_state(T1) 편입. 항목별 실패 격리. "
		+ "신규 INSERT 는 등록 좌표의 법정동 근거(region_resolution)를 요구하고 그 지역을 쓰며, 없으면 보류(held)한다. "
		+ "후속이 빠진 기존 건을 다시 보내면 빠진 것만 보완한다(recovered)")
	@PostMapping("/register-batch")
	public ResponseEntity<ApiResponse<CandidateRegisterResult>> registerBatch(
			@RequestBody @Valid CandidateRegisterRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(candidateRegisterService.registerBatch(request)));
	}

	@Operation(summary = "신규 후보 등록 미리보기", description = "등록 배치와 같은 판정(INSERT·중복 귀속·보류)과 "
		+ "쓸 지역의 근거를 운영·로컬 쓰기 없이 돌려준다. probe=true 면 INSERT 판정 항목마다 운영에 같은 INSERT 를 "
		+ "실행하고 롤백해 운영 제약 통과를 확인한다(IDENTITY 시퀀스만 넘어간다)")
	@PostMapping("/register-preview")
	public ResponseEntity<ApiResponse<List<CandidateRegisterPreview>>> registerPreview(
			@RequestBody @Valid CandidateRegisterRequest request,
			@RequestParam(defaultValue = "false") boolean probe) {
		return ResponseEntity.ok(ApiResponse.ok(candidateRegisterService.preview(request, probe)));
	}

	@Operation(summary = "DELETED 가게 재활성 (검토 후)", description = "등록 배치가 자동으로 하지 않는 별도 경로. "
		+ "이전에 왜 제외됐는지 사람이 검토한 결과(reviewNote)를 받아야 진행한다 — 같은 이유로 다시 지워질 가게를 "
		+ "자동으로 되살리지 않기 위해서다. 반환 false = DELETED 가 아니거나 그새 상태가 바뀌어 반영하지 않음")
	@PostMapping("/{mgmtNo}/reactivate")
	public ResponseEntity<ApiResponse<Boolean>> reactivateAfterReview(
			@PathVariable String mgmtNo,
			@RequestBody @Valid CandidateReactivateRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(
			candidateRegisterService.reactivateAfterReview(mgmtNo, request.reviewNote())));
	}
}
