package sweetgirl.croissant.course.dto.response;

import java.util.List;

/** 새로 부른 사람. 이미 일행이거나 아직 답하지 않은 초대가 있는 사람은 조용히 빠진다. */
public record CourseMemberInviteResponse(List<Long> invitedMemberIds) {
}
