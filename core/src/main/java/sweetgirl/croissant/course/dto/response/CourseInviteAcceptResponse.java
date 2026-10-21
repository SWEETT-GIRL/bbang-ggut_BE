package sweetgirl.croissant.course.dto.response;

public record CourseInviteAcceptResponse(
		Long courseId,
		/** 이미 멤버이거나 본인이 방장이라 아무것도 하지 않았는가. */
		boolean alreadyMember) {
}
