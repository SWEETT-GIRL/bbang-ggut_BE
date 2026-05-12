package sweetgirl.croissant.inquiry.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import sweetgirl.croissant.inquiry.enums.InquiryCategory;

public record InquiryCreateRequest(
		@NotNull InquiryCategory category,
		@NotBlank @Size(max = 200) String title,
		@NotBlank @Size(max = 5000) String content) {
}
