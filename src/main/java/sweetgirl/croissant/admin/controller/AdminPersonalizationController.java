package sweetgirl.croissant.admin.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminInteractionIngestResponse;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.personalization.code.PersonalizationErrorCode;
import sweetgirl.croissant.personalization.dto.response.InteractionIngestResult;
import sweetgirl.croissant.personalization.exception.PersonalizationException;
import sweetgirl.croissant.personalization.service.UserInteractionIngestCommandService;

/** 개인화 배치 수동 실행 — 매일 04:00 배치를 기다리지 않거나, 과거 범위를 백필할 때. */
@RestController
@RequestMapping("/admin/personalization")
@RequiredArgsConstructor
@Tag(name = "Admin Personalization", description = "취향 프로필 배치 관리")
public class AdminPersonalizationController {

	private final UserInteractionIngestCommandService ingestCommandService;

	@PostMapping("/interactions/ingest")
	@Operation(summary = "신호 적재 실행", description = "from(포함)~to(제외) 날짜의 저장·계획·기록·조회·코스 제거·추천 반응을 "
		+ "user_store_interaction 으로 옮긴다. 둘 다 없으면 어제 하루, from 만 있으면 그 하루. 같은 범위를 다시 돌려도 중복되지 "
		+ "않는다. 한 번에 최대 62일(설정).")
	public ApiResponse<AdminInteractionIngestResponse> ingest(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		if (from == null && to == null) {
			return ApiResponse.ok(AdminInteractionIngestResponse.of(ingestCommandService.ingestYesterday()));
		}
		if (from == null) {
			// to 만으로는 범위가 정해지지 않는다 — 한쪽만 온 요청이 500 으로 새지 않게(PR #747 리뷰)
			throw new PersonalizationException(PersonalizationErrorCode.INGEST_RANGE_INVALID);
		}
		LocalDate toOrNextDay = to != null ? to : from.plusDays(1);
		InteractionIngestResult result = ingestCommandService.ingest(from, toOrNextDay);
		return ApiResponse.ok(AdminInteractionIngestResponse.of(result));
	}

	@PostMapping("/interactions/backfill-course-adds")
	@Operation(summary = "담기 백필(코스 주인 기준)", description = "until(제외) 전에 담긴 가게를 코스 주인의 COURSE_ADD 신호로 "
		+ "한 번 옮긴다. until 은 담기 이벤트 배포 날짜(설정 course-add-event-since) 이하만 허용 — 그 뒤는 담은 회원으로 이벤트가 "
		+ "남긴다. 큐레이션 코스(주인 없음)는 제외.")
	public ApiResponse<Integer> backfillCourseAdds(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until) {
		return ApiResponse.ok(ingestCommandService.backfillCourseAddsByOwner(until));
	}
}
