package sweetgirl.croissant.course.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.service.CommunityNotificationService;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseMemberInviteResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseMemberInvite;
import sweetgirl.croissant.course.enums.CourseMemberInviteStatus;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseMemberInviteRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;

/**
 * 앱 안 일행 초대 — 팔로잉 · 팔로워에서 골라 부르고, 받은 사람이 [수락]/[거절]한다. 링크 초대는 그대로 둔다.
 * 수락은 링크 초대 수락과 같은 규칙({@link CourseMemberService#joinLockedCourse})을 탄다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseMemberInviteService {

	private final CourseRepository courseRepository;
	private final CourseMemberInviteRepository inviteRepository;
	private final CourseMemberService courseMemberService;
	private final CourseAccessPolicy courseAccessPolicy;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final SocialMemberReader socialMemberReader;
	private final MemberFollowRepository memberFollowRepository;
	private final MemberBlockRepository memberBlockRepository;
	private final CommunityNotificationService communityNotificationService;

	/**
	 * 방장 · 일행이 사람들을 부른다. 큐레이션 코스 403, 방장이 탈퇴한 코스 404.
	 * 상대가 없거나 게스트 · 봇 · 탈퇴 404, 나 자신 400, 차단 403, 팔로우 관계(어느 쪽이든)가 아니면 400.
	 * 이미 일행이거나 답하지 않은 초대가 있으면 조용히 건너뛴다.
	 */
	public CourseMemberInviteResponse invite(Long inviterId, Long courseId, List<Long> memberIds) {
		memberRestrictionReader.validateCanWrite(inviterId);
		Member inviter = memberReader.getMember(inviterId);
		Course course = courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		courseAccessPolicy.requireEditable(course, inviterId);
		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		if (course.getMember() != null && course.getMember().isDeleted()) {
			throw new CourseException(CourseErrorCode.COURSE_INVITE_INVALID);
		}

		LocalDateTime now = LocalDateTime.now();
		List<Member> invited = new ArrayList<>();
		for (Long memberId : new LinkedHashSet<>(memberIds)) {
			if (inviterId.equals(memberId)) {
				throw new CourseException(CourseErrorCode.COURSE_INVITEE_SELF);
			}
			Member invitee = validateInvitee(inviterId, memberId);
			if (courseAccessPolicy.isOwner(course, memberId) || courseAccessPolicy.isActiveMember(course, memberId)
				|| inviteRepository.existsByCourseIdAndInviteeIdAndStatus(courseId, memberId,
					CourseMemberInviteStatus.PENDING)) {
				continue;
			}
			inviteRepository.save(CourseMemberInvite.of(course, inviter, invitee, now));
			invited.add(invitee);
		}
		communityNotificationService.notifyCourseMemberInvite(inviter, course, invited);
		return new CourseMemberInviteResponse(invited.stream().map(Member::getId).toList());
	}

	private Member validateInvitee(Long inviterId, Long memberId) {
		Member invitee = socialMemberReader.findSocial(memberId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_INVITEE_NOT_FOUND));
		if (memberBlockRepository.existsBetween(inviterId, memberId)) {
			throw new CourseException(CourseErrorCode.COURSE_INVITEE_BLOCKED);
		}
		boolean related = memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(inviterId, memberId)
			|| memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(memberId, inviterId);
		if (!related) {
			throw new CourseException(CourseErrorCode.COURSE_INVITEE_NOT_RELATED);
		}
		return invitee;
	}

	/** 받은 초대를 수락해 일행이 된다. 답하지 않은 초대가 없으면 404. 게스트 · 정원 · 큐레이션 · 방장 탈퇴는 링크 수락과 같다. */
	public CourseInviteAcceptResponse accept(Long memberId, Long courseId) {
		memberRestrictionReader.validateCanWrite(memberId);
		Member member = memberReader.getMember(memberId);
		if (member.isGuest()) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		CourseMemberInvite invite = pendingInvite(courseId, memberId);
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		CourseInviteAcceptResponse response = courseMemberService.joinLockedCourse(member, course);
		invite.accept(LocalDateTime.now());
		return response;
	}

	/** 받은 초대를 거절한다 — 알림 없음. 답하지 않은 초대가 없으면 404. */
	public void decline(Long memberId, Long courseId) {
		pendingInvite(courseId, memberId).decline(LocalDateTime.now());
	}

	private CourseMemberInvite pendingInvite(Long courseId, Long memberId) {
		return inviteRepository
			.findByCourseIdAndInviteeIdAndStatus(courseId, memberId, CourseMemberInviteStatus.PENDING)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_MEMBER_INVITE_NOT_FOUND));
	}
}
