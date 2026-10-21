package sweetgirl.croissant.inquiry.dto.response;

import java.time.LocalDateTime;

import sweetgirl.croissant.inquiry.entity.Inquiry;

public record InquiryCreateResponse(
		Long inquiryId,
		LocalDateTime createdAt) {

	public static InquiryCreateResponse from(Inquiry inquiry) {
		return new InquiryCreateResponse(inquiry.getId(), inquiry.getCreatedAt());
	}
}
