package sweetgirl.croissant.member.dto.request;

public record ConsentUpdateRequest(
		Boolean agreeToMarketing,
		Boolean agreeToNightMarketing) {
}
