package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseMemberInviteService")
class CourseMemberInviteServiceTest {

	private static final Long OWNER = 1L;
	private static final Long INVITEE = 2L;
	private static final Long COURSE = 10L;

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseMemberInviteRepository inviteRepository;
	@Mock
	private CourseMemberService courseMemberService;
	@Mock
	private CourseAccessPolicy courseAccessPolicy;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private CommunityNotificationService communityNotificationService;

	@InjectMocks
	private CourseMemberInviteService inviteService;

	private Member member(Long id) {
		Member member = mock(Member.class);
		lenient().when(member.getId()).thenReturn(id);
		lenient().when(member.isDeleted()).thenReturn(false);
		lenient().when(member.isGuest()).thenReturn(false);
		return member;
	}

	private Course course(CourseType type) {
		// member() 가 다른 mock 을 스텁하므로 when(...) 안에서 부르면 UnfinishedStubbing 이 난다 — 먼저 만든다.
		Member owner = member(OWNER);
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(COURSE);
		lenient().when(course.getType()).thenReturn(type);
		lenient().when(course.getMember()).thenReturn(owner);
		return course;
	}

	private void inviterAndCourse(Course course) {
		Member inviter = member(OWNER);
		given(memberReader.getMember(OWNER)).willReturn(inviter);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE)).willReturn(Optional.of(course));
	}

	private Member socialInvitee() {
		Member invitee = member(INVITEE);
		given(socialMemberReader.findSocial(INVITEE)).willReturn(Optional.of(invitee));
		return invitee;
	}

	@Test
	@DisplayName("팔로우 관계인 사람을 부르면 초대 행을 저장하고 알린다")
	void invitesFollower() {
		Course course = course(CourseType.CUSTOM);
		inviterAndCourse(course);
		Member invitee = socialInvitee();
		// 팔로워(상대가 나를 팔로우)만으로도 된다
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(OWNER, INVITEE)).willReturn(false);
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(INVITEE, OWNER)).willReturn(true);

		CourseMemberInviteResponse response = inviteService.invite(OWNER, COURSE, List.of(INVITEE));

		then(memberRestrictionReader).should().validateCanWrite(OWNER);
		then(courseAccessPolicy).should().requireEditable(course, OWNER);
		ArgumentCaptor<CourseMemberInvite> captor = ArgumentCaptor.forClass(CourseMemberInvite.class);
		then(inviteRepository).should().save(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(CourseMemberInviteStatus.PENDING);
		then(communityNotificationService).should().notifyCourseMemberInvite(any(), eq(course), eq(List.of(invitee)));
		assertThat(response.invitedMemberIds()).containsExactly(INVITEE);
	}

	@Test
	@DisplayName("이미 일행이거나 답하지 않은 초대가 있으면 조용히 건너뛴다")
	void skipsMembersAndPending() {
		Course course = course(CourseType.CUSTOM);
		inviterAndCourse(course);
		socialInvitee();
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(OWNER, INVITEE)).willReturn(true);
		given(courseAccessPolicy.isActiveMember(course, INVITEE)).willReturn(true);

		CourseMemberInviteResponse response = inviteService.invite(OWNER, COURSE, List.of(INVITEE));

		then(inviteRepository).should(never()).save(any());
		then(communityNotificationService).should().notifyCourseMemberInvite(any(), eq(course), eq(List.of()));
		assertThat(response.invitedMemberIds()).isEmpty();
	}

	@Test
	@DisplayName("팔로우 관계가 아니면 400, 차단이면 403, 나 자신이면 400, 큐레이션 코스면 403")
	void rejects() {
		Course course = course(CourseType.CUSTOM);
		inviterAndCourse(course);
		socialInvitee();

		assertThatThrownBy(() -> inviteService.invite(OWNER, COURSE, List.of(INVITEE)))
			.hasMessage(CourseErrorCode.COURSE_INVITEE_NOT_RELATED.getMessage());

		given(memberBlockRepository.existsBetween(OWNER, INVITEE)).willReturn(true);
		assertThatThrownBy(() -> inviteService.invite(OWNER, COURSE, List.of(INVITEE)))
			.hasMessage(CourseErrorCode.COURSE_INVITEE_BLOCKED.getMessage());

		assertThatThrownBy(() -> inviteService.invite(OWNER, COURSE, List.of(OWNER)))
			.hasMessage(CourseErrorCode.COURSE_INVITEE_SELF.getMessage());

		Course curated = course(CourseType.CURATED);
		given(courseRepository.findByIdAndDeletedAtIsNull(COURSE)).willReturn(Optional.of(curated));
		assertThatThrownBy(() -> inviteService.invite(OWNER, COURSE, List.of(INVITEE)))
			.isInstanceOf(CourseException.class)
			.hasMessage(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
	}

	@Test
	@DisplayName("수락: 링크 수락과 같은 합류 규칙을 타고 초대는 ACCEPTED")
	void accept() {
		Member me = member(INVITEE);
		given(memberReader.getMember(INVITEE)).willReturn(me);
		Course course = course(CourseType.CUSTOM);
		Member inviter = member(OWNER);
		CourseMemberInvite invite = CourseMemberInvite.of(course, inviter, me, LocalDateTime.now());
		given(inviteRepository.findByCourseIdAndInviteeIdAndStatus(COURSE, INVITEE, CourseMemberInviteStatus.PENDING))
			.willReturn(Optional.of(invite));
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE)).willReturn(Optional.of(course));
		given(courseMemberService.joinLockedCourse(me, course))
			.willReturn(new CourseInviteAcceptResponse(COURSE, false));

		CourseInviteAcceptResponse response = inviteService.accept(INVITEE, COURSE);

		assertThat(response.courseId()).isEqualTo(COURSE);
		assertThat(invite.getStatus()).isEqualTo(CourseMemberInviteStatus.ACCEPTED);
		assertThat(invite.getRespondedAt()).isNotNull();
	}

	@Test
	@DisplayName("게스트는 수락 403, 받은 초대가 없으면 404")
	void acceptRejects() {
		Member guest = member(INVITEE);
		given(guest.isGuest()).willReturn(true);
		given(memberReader.getMember(INVITEE)).willReturn(guest);
		assertThatThrownBy(() -> inviteService.accept(INVITEE, COURSE))
			.hasMessage(CourseErrorCode.COURSE_FORBIDDEN.getMessage());

		given(inviteRepository.findByCourseIdAndInviteeIdAndStatus(COURSE, INVITEE, CourseMemberInviteStatus.PENDING))
			.willReturn(Optional.empty());
		assertThatThrownBy(() -> inviteService.decline(INVITEE, COURSE))
			.hasMessage(CourseErrorCode.COURSE_MEMBER_INVITE_NOT_FOUND.getMessage());
		then(courseMemberService).should(never()).joinLockedCourse(any(), any());
	}

	@Test
	@DisplayName("거절: DECLINED 로 끝내고 합류하지 않는다")
	void decline() {
		Course course = course(CourseType.CUSTOM);
		Member inviter = member(OWNER);
		Member invitee = member(INVITEE);
		CourseMemberInvite invite = CourseMemberInvite.of(course, inviter, invitee, LocalDateTime.now());
		given(inviteRepository.findByCourseIdAndInviteeIdAndStatus(COURSE, INVITEE, CourseMemberInviteStatus.PENDING))
			.willReturn(Optional.of(invite));

		inviteService.decline(INVITEE, COURSE);

		assertThat(invite.getStatus()).isEqualTo(CourseMemberInviteStatus.DECLINED);
		then(courseMemberService).shouldHaveNoInteractions();
		then(inviteRepository).should(never()).save(any());
	}
}
