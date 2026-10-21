package sweetgirl.croissant.monthlyreport.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 통계와 함께 저장하는 사진 출처. 파일 키는 이 식별자로 조회 시점에 확인한다. */
public record MonthlyReportPhotoSource(@NotNull @Positive Long recordId, @NotNull UUID photoReferenceId) {
}
