package sweetgirl.croissant.breadlog.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 수정 가능 범위를 DTO로 한정한다. 사진·가게·공개 여부·타입은 받지 않는다. */
public record TutorialRecordEditRequest(
		@Size(max = 500) String memo,
		@NotBlank String towerBread,
		@Valid BreadTagRatingRequest rating) {
}
