package sweetgirl.croissant.monthlyreport.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** schemaVersion 1: 미집계 점수는 null로 유지하고, 집계된 수치만 0을 허용한다. */
public record MonthlyReportResponse(
		@Min(1) @Max(1) int schemaVersion,
		@NotNull @Pattern(regexp = "20[0-9]{2}-(0[1-9]|1[0-2])") String yearMonth,
		@NotNull @Min(1) @Max(MAX_SAFE_INTEGER) Long memberId,
		@NotBlank @Size(max = 100) String nickname,
		@NotNull @Valid Character character,
		@NotNull @Valid Planet planet,
		@NotNull @Valid Summary summary,
		@NotNull @Valid Stores stores,
		@NotNull @Size(max = 100) List<@NotNull @Valid Bread> breads,
		@NotNull @Valid Activity activity,
		@NotNull @Valid Comparison comparison,
		@NotNull @Size(max = 500) List<@NotNull @Valid Town> towns) {

	public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

	public MonthlyReportResponse {
		// 불변 스냅샷이 호출자의 리스트 변경으로 달라지지 않게 한다. null 항목은 검증기가 거절한다.
		breads = breads == null ? null : Collections.unmodifiableList(new ArrayList<>(breads));
		towns = towns == null ? null : Collections.unmodifiableList(new ArrayList<>(towns));
	}

	public MonthlyReportResponse withBreads(List<Bread> visibleBreads) {
		return new MonthlyReportResponse(schemaVersion, yearMonth, memberId, nickname, character, planet,
			summary, stores, visibleBreads, activity, comparison, towns);
	}

	public MonthlyReportResponse withStores(Stores currentStores) {
		return new MonthlyReportResponse(schemaVersion, yearMonth, memberId, nickname, character, planet,
			summary, currentStores, breads, activity, comparison, towns);
	}

	public MonthlyReportResponse withNickname(String currentNickname) {
		return new MonthlyReportResponse(schemaVersion, yearMonth, memberId, currentNickname, character, planet,
			summary, stores, breads, activity, comparison, towns);
	}

	public record Character(
			@NotBlank @Size(max = 100) String code,
			@NotBlank @Size(max = 100) String name,
			@Size(max = 2048) String imageKey) {
	}

	public record Planet(@NotBlank @Size(max = 100) String code, @NotBlank @Size(max = 100) String name) {
	}

	public record Summary(
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long records,
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long breads,
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long stores) {
	}

	public record Stores(
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long viewed,
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long saved,
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long planned,
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long courses) {
	}

	/** 발행 스냅샷에 담긴 빵 종류별 집계 항목: 표시 이름, 집계 횟수, 대표 사진 키. */
	public record Bread(
			@NotBlank @Size(max = 100) String name,
			@NotNull @Min(1) @Max(MAX_SAFE_INTEGER) Long count,
			@Size(max = 2048) String imageKey) {
	}

	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Activity(
			@NotNull @Pattern(regexp = "explore|save|plan|record|seed") String type,
			@JsonProperty(required = true) @Min(0) @Max(MAX_SAFE_INTEGER) Long totalScore,
			@Valid Abilities abilities,
			@Valid Artwork artwork) {
	}

	public record Abilities(
			@NotNull @Valid Ability explore,
			@NotNull @Valid Ability save,
			@NotNull @Valid Ability plan,
			@NotNull @Valid Ability record) {

		public List<Ability> values() {
			return List.of(explore, save, plan, record);
		}
	}

	public record Ability(
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long score,
			@NotNull @Min(1) @Max(MAX_SAFE_INTEGER) Long maxScore) {
	}

	public record Artwork(
			@NotBlank @Size(max = 100) String characterCode,
			@NotBlank @Size(max = 100) String planetCode,
			@NotNull @Pattern(regexp = "explore|save|plan|record|seed") String type,
			@NotBlank @Size(max = 100) String version,
			@NotBlank @Size(max = 2048) String imageKey) {
	}

	/** 전월 집계가 없으면 previousRecords는 null이며 프론트는 비교 카드를 생략한다. */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record Comparison(
			@JsonProperty(required = true) @Min(0) @Max(MAX_SAFE_INTEGER) Long previousRecords,
			@NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long currentRecords) {
	}

	public record Town(
			@NotBlank @Size(max = 100) String name,
			@JsonInclude(JsonInclude.Include.NON_NULL) @Size(min = 1, max = 100) String region) {
	}
}
