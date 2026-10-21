package sweetgirl.dataops.candidate.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * DELETED 가게 재활성 요청 (v3 P1-3) — 등록 배치와 분리된 경로.
 *
 * @param reviewNote 이전 제외 사유를 검토한 결과. 왜 되살려도 되는지 남긴다 — 비어 있으면 진행하지 않는다
 */
public record CandidateReactivateRequest(@NotBlank String reviewNote) {
}
