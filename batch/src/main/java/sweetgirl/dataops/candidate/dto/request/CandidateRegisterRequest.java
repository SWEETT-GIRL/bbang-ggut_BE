package sweetgirl.dataops.candidate.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 신규 후보 등록 배치 — 워커가 네이버 플레이스 실측값(상호·주소·좌표·전화)을 채워 보낸다.
 *
 * 지역은 싣지 않는다 (v3 P4-4). 서버가 이 좌표로 역산한 법정동 근거(region_resolution, 후보 관리번호)를 찾아
 * 그 지역을 쓴다 — 워커는 등록 전에 region_resolver 로 같은 좌표의 근거를 남겨 둔다. 근거가 없으면 신규 INSERT 를
 * 보류한다. 기존 가게 귀속(중복 해소)은 위치를 쓰지 않으므로 근거 없이 진행한다.
 */
public record CandidateRegisterRequest(@NotEmpty @Valid List<Item> candidates) {

	public record Item(
		@NotBlank String mgmtNo,
		@NotBlank String name,
		@NotBlank String address,
		/** 법정동 근거를 찾는 조회 좌표이기도 하다 — 역산한 좌표와 소수 6자리까지 같아야 한다 */
		@NotNull Double longitude,
		@NotNull Double latitude,
		String phone) {
	}
}
