package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseInvitePreviewResponse;
import sweetgirl.croissant.course.dto.response.CourseInviteResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseInvite;
import sweetgirl.croissant.course.entity.CourseMember;
import sweetgirl.croissant.course.entity.SavedCourse;
import sweetgirl.croissant.course.enums.CourseInviteState;
import sweetgirl.croissant.course.enums.CourseType;
import sweetgirl.croissant.course.enums.CourseVisibility;
import sweetgirl.croissant.course.exception.CourseException;
import sweetgirl.croissant.course.repository.CourseInviteRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.course.util.CollabTokenGenerator;
import sweetgirl.croissant.follow.service.FollowCommandService;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourseMemberService")
class CourseMemberServiceTest {

	private static final Long OWNER_ID = 1L;
	private static final Long MEMBER_ID = 2L;
	private static final Long OTHER_ID = 3L;
	private static final Long COURSE_ID = 10L;
	private static final String TOKEN = "invite-token";

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseMemberRepository courseMemberRepository;
	@Mock
	private CourseInviteRepository courseInviteRepository;
	@Mock
	private SavedCourseRepository savedCourseRepository;
	@Mock
	private CourseStoreMemoRepository courseStoreMemoRepository;
	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private FollowCommandService followCommandService;

	@InjectMocks
	private CourseMemberService courseMemberService;

	/** 권한 판정은 CourseAccessPolicy 로 갔지만 근거는 같은 courseMemberRepository 다 — 실체를 넣어 기존 스텁이 그대로 산다. */
	@BeforeEach
	void injectAccessPolicy() {
		ReflectionTestUtils.setField(courseMemberService, "courseAccessPolicy",
			new CourseAccessPolicy(courseMemberRepository));
	}

	// ───────────────────────── helpers ─────────────────────────

	private Member mockMember(Long id, String nickname) {
		Member member = mock(Member.class);
		lenient().when(member.getId()).thenReturn(id);
		lenient().when(member.getNickname()).thenReturn(nickname);
		return member;
	}

	private Member owner() {
		return mockMember(OWNER_ID, "다나");
	}

	private Course mockCourse(CourseType type, Member courseOwner, boolean isPublic) {
		Course course = mock(Course.class);
		lenient().when(course.getId()).thenReturn(COURSE_ID);
		lenient().when(course.getType()).thenReturn(type);
		lenient().when(course.getMember()).thenReturn(courseOwner);
		lenient().when(course.isPublic()).thenReturn(isPublic);
		lenient().when(course.getTitle()).thenReturn("성수 소금빵 투어");
		return course;
	}

	private CourseInvite inviteExpiringAt(Course course, LocalDateTime expiresAt) {
		return CourseInvite.of(course, owner(), TOKEN, expiresAt);
	}

	private CourseMember row(Course course, Long memberId) {
		return CourseMember.of(course, mockMember(memberId, "닉" + memberId));
	}

	private void givenLockedCourse(Course course) {
		given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
	}

	private void givenOwnerRowExists(Course course) {
		CourseMember ownerRow = row(course, OWNER_ID);
		lenient().when(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OWNER_ID))
			.thenReturn(Optional.of(ownerRow));
	}

	private void givenActiveMember(Long memberId, boolean active) {
		lenient().when(courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(COURSE_ID, memberId))
			.thenReturn(active);
	}

	// ───────────────────────── createInvite ─────────────────────────

	@Nested
	@DisplayName("createInvite")
	class CreateInvite {

		@Test
		@DisplayName("처음 부르면 방장 행(이름 = 닉네임)을 만들고 7일짜리 토큰을 새로 발급한다")
		void firstTime_createsOwnerRowAndIssuesToken() {
			Member courseOwner = owner();
			Course course = mockCourse(CourseType.CUSTOM, courseOwner, false);
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			givenLockedCourse(course);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OWNER_ID))
				.willReturn(Optional.empty());
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.empty());

			CourseInviteResponse response = courseMemberService.createInvite(OWNER_ID, COURSE_ID);

			ArgumentCaptor<CourseMember> rowCaptor = ArgumentCaptor.forClass(CourseMember.class);
			then(courseMemberRepository).should().save(rowCaptor.capture());
			assertThat(rowCaptor.getValue().getId().getMemberId()).isEqualTo(OWNER_ID);

			ArgumentCaptor<CourseInvite> inviteCaptor = ArgumentCaptor.forClass(CourseInvite.class);
			then(courseInviteRepository).should().save(inviteCaptor.capture());
			CourseInvite issued = inviteCaptor.getValue();
			assertThat(issued.getToken()).hasSize(CollabTokenGenerator.TOKEN_LENGTH);
			assertThat(issued.getExpiresAt()).isCloseTo(LocalDateTime.now().plusDays(7), within(1, ChronoUnit.MINUTES));
			assertThat(issued.getCreatedBy()).isSameAs(courseOwner);

			assertThat(response.token()).isEqualTo(issued.getToken());
			assertThat(response.expiresAt()).isEqualTo(issued.getExpiresAt());
			assertThat(response.reused()).isFalse();
			then(courseRepository).should().bumpVersion(COURSE_ID);
		}

		@Test
		@DisplayName("살아있는 토큰이 하루 이상 남았으면 그대로 돌려준다(reused)")
		void activeTokenWithEnoughRemaining_reused() {
			Member courseOwner = owner();
			Course course = mockCourse(CourseType.CUSTOM, courseOwner, false);
			CourseInvite current = inviteExpiringAt(course, LocalDateTime.now().plusDays(3));
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			givenLockedCourse(course);
			givenOwnerRowExists(course);
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.of(current));

			CourseInviteResponse response = courseMemberService.createInvite(OWNER_ID, COURSE_ID);

			assertThat(response.token()).isEqualTo(TOKEN);
			assertThat(response.reused()).isTrue();
			assertThat(current.getDeletedAt()).isNull();
			then(courseInviteRepository).should(never()).save(any());
			then(courseMemberRepository).should(never()).save(any());
			then(courseRepository).should(never()).bumpVersion(any());
		}

		@Test
		@DisplayName("재사용 경계 — 하루에서 1초 남으면 재사용, 하루에서 1초 모자라면 재발급")
		void reuseBoundary_oneDayRemaining() {
			Member courseOwner = owner();
			Course course = mockCourse(CourseType.CUSTOM, courseOwner, false);
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			givenLockedCourse(course);
			givenOwnerRowExists(course);
			CourseInvite justEnough = inviteExpiringAt(course, LocalDateTime.now().plusDays(1).plusSeconds(1));
			CourseInvite justShort = inviteExpiringAt(course, LocalDateTime.now().plusDays(1).minusSeconds(1));
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.of(justEnough))
				.willReturn(Optional.of(justShort));

			assertThat(courseMemberService.createInvite(OWNER_ID, COURSE_ID).reused()).isTrue();
			assertThat(courseMemberService.createInvite(OWNER_ID, COURSE_ID).reused()).isFalse();
			assertThat(justEnough.getDeletedAt()).isNull();
			assertThat(justShort.getDeletedAt()).isNotNull();
			then(courseInviteRepository).should().save(any());
		}

		@Test
		@DisplayName("남은 기간이 하루 미만이면 재발급하고 이전 토큰은 무효화한다 — 보내는 링크는 항상 하루 이상 산다")
		void activeTokenExpiringSoon_reissuedAndOldRevoked() {
			Member courseOwner = owner();
			Course course = mockCourse(CourseType.CUSTOM, courseOwner, false);
			CourseInvite current = inviteExpiringAt(course, LocalDateTime.now().plusHours(12));
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			givenLockedCourse(course);
			givenOwnerRowExists(course);
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.of(current));

			CourseInviteResponse response = courseMemberService.createInvite(OWNER_ID, COURSE_ID);

			assertThat(response.reused()).isFalse();
			assertThat(response.token()).isNotEqualTo(TOKEN);
			assertThat(current.getDeletedAt()).isNotNull();
			then(courseInviteRepository).should().save(any(CourseInvite.class));
		}

		@Test
		@DisplayName("만료된 토큰이 남아 있어도 재발급하고 그 행은 무효화한다")
		void expiredToken_reissued() {
			Member courseOwner = owner();
			Course course = mockCourse(CourseType.CUSTOM, courseOwner, false);
			CourseInvite expired = inviteExpiringAt(course, LocalDateTime.now().minusHours(1));
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			givenLockedCourse(course);
			givenOwnerRowExists(course);
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.of(expired));

			CourseInviteResponse response = courseMemberService.createInvite(OWNER_ID, COURSE_ID);

			assertThat(response.reused()).isFalse();
			assertThat(expired.getDeletedAt()).isNotNull();
		}

		@Test
		@DisplayName("방장이 아닌 활성 멤버도 링크를 만들 수 있고, 만든 사람은 그 멤버다")
		void activeMember_allowed() {
			Member member = mockMember(MEMBER_ID, "지수");
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			given(memberReader.getMember(MEMBER_ID)).willReturn(member);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, true);
			givenOwnerRowExists(course);
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.empty());

			courseMemberService.createInvite(MEMBER_ID, COURSE_ID);

			ArgumentCaptor<CourseInvite> inviteCaptor = ArgumentCaptor.forClass(CourseInvite.class);
			then(courseInviteRepository).should().save(inviteCaptor.capture());
			assertThat(inviteCaptor.getValue().getCreatedBy()).isSameAs(member);
			then(courseMemberRepository).should(never()).save(any());
			then(courseRepository).should(never()).bumpVersion(any());
		}

		@Test
		@DisplayName("탈퇴한 방장의 닫힌 행은 멤버가 링크를 만들어도 되살리지 않는다")
		void deletedOwnerRow_notRevived() {
			Member member = mockMember(MEMBER_ID, "지수");
			Member deletedOwner = owner();
			given(deletedOwner.isDeleted()).willReturn(true);
			Course course = mockCourse(CourseType.CUSTOM, deletedOwner, false);
			given(memberReader.getMember(MEMBER_ID)).willReturn(member);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, true);
			CourseMember closedOwnerRow = row(course, OWNER_ID);
			closedOwnerRow.leave();
			lenient().when(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OWNER_ID))
				.thenReturn(Optional.of(closedOwnerRow));
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.empty());

			courseMemberService.createInvite(MEMBER_ID, COURSE_ID);

			assertThat(closedOwnerRow.isDeleted()).isTrue();
			then(courseRepository).should(never()).bumpVersion(any());
		}

		@Test
		@DisplayName("멤버가 아닌 사람은 COURSE_FORBIDDEN")
		void outsider_forbidden() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			Member outsider = mockMember(OTHER_ID, "남");
			given(memberReader.getMember(OTHER_ID)).willReturn(outsider);
			givenLockedCourse(course);
			givenActiveMember(OTHER_ID, false);

			assertThatThrownBy(() -> courseMemberService.createInvite(OTHER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
			then(courseInviteRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("큐레이션 코스는 멤버 행이 있어도 초대를 만들 수 없다 — CURATED 검사가 멤버 조회보다 먼저다")
		void curated_forbidden() {
			Course course = mockCourse(CourseType.CURATED, null, true);
			Member member = mockMember(MEMBER_ID, "지수");
			given(memberReader.getMember(MEMBER_ID)).willReturn(member);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, true);

			assertThatThrownBy(() -> courseMemberService.createInvite(MEMBER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
			then(courseMemberRepository).shouldHaveNoInteractions();
			then(courseInviteRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("작성 제한(밴) 계정은 코스를 보기도 전에 막힌다")
		void restricted_blockedBeforeAnything() {
			willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
				.given(memberRestrictionReader).validateCanWrite(OWNER_ID);

			assertThatThrownBy(() -> courseMemberService.createInvite(OWNER_ID, COURSE_ID))
				.isInstanceOf(MemberException.class);
			then(courseRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("지운 코스면 COURSE_NOT_FOUND")
		void deletedCourse_notFound() {
			Member courseOwner = owner();
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> courseMemberService.createInvite(OWNER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
		}
	}

	// ───────────────────────── previewInvite ─────────────────────────

	@Nested
	@DisplayName("previewInvite")
	class PreviewInvite {

		private CourseInvite givenInvite(Course course, LocalDateTime expiresAt, long memberCount) {
			CourseInvite invite = inviteExpiringAt(course, expiresAt);
			given(courseInviteRepository.findWithCourseOwnerByTokenAndDeletedAtIsNull(TOKEN))
				.willReturn(Optional.of(invite));
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(memberCount);
			return invite;
		}

		@Test
		@DisplayName("모르는 토큰은 INVALID 이고 코스 정보는 비어 있다")
		void unknownToken_invalid() {
			given(courseInviteRepository.findWithCourseOwnerByTokenAndDeletedAtIsNull(TOKEN))
				.willReturn(Optional.empty());

			CourseInvitePreviewResponse response = courseMemberService.previewInvite(TOKEN, null);

			assertThat(response.state()).isEqualTo(CourseInviteState.INVALID);
			assertThat(response.courseId()).isNull();
			assertThat(response.memberLimit()).isEqualTo(8);
			assertThat(response.viewer()).isNull();
		}

		@Test
		@DisplayName("비로그인 미리보기: OK 상태에 코스 제목·방장 이름·인원만 주고 viewer 는 null")
		void anonymous_ok() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			LocalDateTime expiresAt = LocalDateTime.now().plusDays(5);
			givenInvite(course, expiresAt, 3);

			CourseInvitePreviewResponse response = courseMemberService.previewInvite(TOKEN, null);

			assertThat(response.state()).isEqualTo(CourseInviteState.OK);
			assertThat(response.courseId()).isEqualTo(COURSE_ID);
			assertThat(response.title()).isEqualTo("성수 소금빵 투어");
			assertThat(response.ownerName()).isEqualTo("다나");
			assertThat(response.memberCount()).isEqualTo(3);
			assertThat(response.memberLimit()).isEqualTo(8);
			assertThat(response.expiresAt()).isEqualTo(expiresAt);
			assertThat(response.viewer()).isNull();
			// 미리보기는 코스·방장을 같이 읽는 조회를 쓴다 — 수락용 조회가 아니다
			then(courseInviteRepository).should(never()).findByTokenAndDeletedAtIsNull(any());
		}

		@Test
		@DisplayName("만료된 링크는 예외 대신 EXPIRED")
		void expired_state() {
			givenInvite(mockCourse(CourseType.CUSTOM, owner(), false), LocalDateTime.now().minusMinutes(1), 2);

			assertThat(courseMemberService.previewInvite(TOKEN, null).state()).isEqualTo(CourseInviteState.EXPIRED);
		}

		@Test
		@DisplayName("방장 포함 8명이면 FULL")
		void full_state() {
			givenInvite(mockCourse(CourseType.CUSTOM, owner(), false), LocalDateTime.now().plusDays(5), 8);

			assertThat(courseMemberService.previewInvite(TOKEN, null).state()).isEqualTo(CourseInviteState.FULL);
		}

		@Test
		@DisplayName("방장이 탈퇴한 코스는 DELETED — 넘길 사람 없이 방장이 사라진 코스에 들어오게 두지 않는다")
		void deletedOwner_isDeletedState() {
			Member deletedOwner = owner();
			given(deletedOwner.isDeleted()).willReturn(true);
			Course course = mockCourse(CourseType.CUSTOM, deletedOwner, false);
			givenInvite(course, LocalDateTime.now().plusDays(5), 1L);

			assertThat(courseMemberService.previewInvite(TOKEN, null).state()).isEqualTo(CourseInviteState.DELETED);
		}

		@Test
		@DisplayName("코스가 지워졌으면 만료 여부와 무관하게 DELETED")
		void deletedCourse_state() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			given(course.isDeleted()).willReturn(true);
			givenInvite(course, LocalDateTime.now().minusDays(1), 2);

			assertThat(courseMemberService.previewInvite(TOKEN, null).state()).isEqualTo(CourseInviteState.DELETED);
		}

		@Test
		@DisplayName("로그인한 방장이 보면 viewer.isOwner 만 true")
		void viewerOwner() {
			givenInvite(mockCourse(CourseType.CUSTOM, owner(), false), LocalDateTime.now().plusDays(5), 2);

			CourseInvitePreviewResponse response = courseMemberService.previewInvite(TOKEN, OWNER_ID);

			assertThat(response.viewer().isOwner()).isTrue();
			assertThat(response.viewer().isMember()).isFalse();
		}

		@Test
		@DisplayName("로그인한 활성 멤버가 보면 viewer.isMember 만 true, 제3자는 둘 다 false")
		void viewerMemberAndOutsider() {
			givenInvite(mockCourse(CourseType.CUSTOM, owner(), false), LocalDateTime.now().plusDays(5), 2);
			givenActiveMember(MEMBER_ID, true);
			givenActiveMember(OTHER_ID, false);

			CourseInvitePreviewResponse memberView = courseMemberService.previewInvite(TOKEN, MEMBER_ID);
			CourseInvitePreviewResponse outsiderView = courseMemberService.previewInvite(TOKEN, OTHER_ID);

			assertThat(memberView.viewer().isMember()).isTrue();
			assertThat(memberView.viewer().isOwner()).isFalse();
			assertThat(outsiderView.viewer().isMember()).isFalse();
			assertThat(outsiderView.viewer().isOwner()).isFalse();
		}
	}

	// ───────────────────────── acceptInvite ─────────────────────────

	@Nested
	@DisplayName("acceptInvite")
	class AcceptInvite {

		private Member joiner() {
			Member member = mockMember(MEMBER_ID, "지수");
			lenient().when(member.isGuest()).thenReturn(false);
			given(memberReader.getMember(MEMBER_ID)).willReturn(member);
			return member;
		}

		private CourseInvite givenValidInvite(Course course) {
			CourseInvite invite = inviteExpiringAt(course, LocalDateTime.now().plusDays(5));
			given(courseInviteRepository.findByTokenAndDeletedAtIsNull(TOKEN)).willReturn(Optional.of(invite));
			return invite;
		}

		@Test
		@DisplayName("정상 수락: 멤버 행 저장, 찜 해제, 공개 코스는 비공개로")
		void success_joinsUnsavesAndMakesPrivate() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), true);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, false);
			givenOwnerRowExists(course);
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(1L);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.empty());
			SavedCourse saved = mock(SavedCourse.class);
			given(savedCourseRepository.findByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(Optional.of(saved));
			// 헬퍼가 다른 mock 을 스텁하므로 given(...) 안에서 부르면 UnfinishedStubbing 이 난다 — 먼저 만든다.
			List<CourseMember> members = List.of(row(course, OWNER_ID), row(course, MEMBER_ID));
			given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(COURSE_ID))
				.willReturn(members);

			CourseInviteAcceptResponse response = courseMemberService.acceptInvite(MEMBER_ID, TOKEN);

			assertThat(response.courseId()).isEqualTo(COURSE_ID);
			assertThat(response.alreadyMember()).isFalse();
			// 일행 전원과 서로 팔로우한다 — 나 자신은 빼고
			then(followCommandService).should().followEachOther(MEMBER_ID, OWNER_ID);
			then(followCommandService).should(never()).followEachOther(MEMBER_ID, MEMBER_ID);
			ArgumentCaptor<CourseMember> rowCaptor = ArgumentCaptor.forClass(CourseMember.class);
			then(courseMemberRepository).should().save(rowCaptor.capture());
			assertThat(rowCaptor.getValue().getId().getMemberId()).isEqualTo(MEMBER_ID);
			then(savedCourseRepository).should().delete(saved);
			then(course).should().updateVisibility(CourseVisibility.PRIVATE);
			then(courseRepository).should().bumpVersion(COURSE_ID);
		}

		@Test
		@DisplayName("비공개 코스는 공개 여부를 건드리지 않고, 찜이 없으면 지우지 않는다")
		void privateCourse_untouched() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, false);
			givenOwnerRowExists(course);
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(1L);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.empty());
			given(savedCourseRepository.findByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(Optional.empty());

			courseMemberService.acceptInvite(MEMBER_ID, TOKEN);

			then(course).should(never()).updateVisibility(any());
			then(savedCourseRepository).should(never()).delete(any());
		}

		@Test
		@DisplayName("나갔던 사람은 같은 행을 되살린다 — 새 행을 저장하지 않는다")
		void leftMember_rejoinsSameRow() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, false);
			givenOwnerRowExists(course);
			CourseMember left = row(course, MEMBER_ID);
			left.leave();
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(1L);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.of(left));
			given(savedCourseRepository.findByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(Optional.empty());

			CourseInviteAcceptResponse response = courseMemberService.acceptInvite(MEMBER_ID, TOKEN);

			assertThat(response.alreadyMember()).isFalse();
			assertThat(left.isDeleted()).isFalse();
			then(courseMemberRepository).should(never()).save(any());
			then(courseRepository).should().bumpVersion(COURSE_ID);
		}

		@Test
		@DisplayName("방장 행이 없으면 정원을 세기 전에 만든다 — 정원엔 방장이 들어간다")
		void ownerRowMissing_createdBeforeCount() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, false);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OWNER_ID))
				.willReturn(Optional.empty());
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.empty());
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(1L);
			given(savedCourseRepository.findByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(Optional.empty());

			courseMemberService.acceptInvite(MEMBER_ID, TOKEN);

			ArgumentCaptor<CourseMember> rowCaptor = ArgumentCaptor.forClass(CourseMember.class);
			then(courseMemberRepository).should(org.mockito.Mockito.times(2)).save(rowCaptor.capture());
			assertThat(rowCaptor.getAllValues()).extracting(saved -> saved.getId().getMemberId())
				.containsExactly(OWNER_ID, MEMBER_ID);
			then(courseRepository).should().bumpVersion(COURSE_ID);
		}

		@Test
		@DisplayName("락을 기다리는 동안 방장이 링크를 재발급했으면 COURSE_INVITE_INVALID — 락 안에서 초대를 다시 확인한다")
		void revokedWhileWaitingForLock_invalid() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			CourseInvite invite = inviteExpiringAt(course, LocalDateTime.now().plusDays(5));
			given(courseInviteRepository.findByTokenAndDeletedAtIsNull(TOKEN))
				.willReturn(Optional.of(invite))
				.willReturn(Optional.empty());
			givenLockedCourse(course);

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_INVITE_INVALID.getMessage());
			then(courseMemberRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("방장이 탈퇴한 코스의 초대는 COURSE_INVITE_INVALID — 들어오면 탈퇴자가 방장으로 되살아난다")
		void deletedOwner_invalid() {
			joiner();
			Member deletedOwner = owner();
			given(deletedOwner.isDeleted()).willReturn(true);
			Course course = mockCourse(CourseType.CUSTOM, deletedOwner, false);
			givenValidInvite(course);
			givenLockedCourse(course);

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_INVITE_INVALID.getMessage());
			then(courseMemberRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("무효 토큰은 COURSE_INVITE_INVALID")
		void invalidToken() {
			joiner();
			given(courseInviteRepository.findByTokenAndDeletedAtIsNull(TOKEN)).willReturn(Optional.empty());

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_INVITE_INVALID.getMessage());
		}

		@Test
		@DisplayName("만료 토큰은 COURSE_INVITE_EXPIRED 이고 코스 락을 잡지 않는다")
		void expiredToken() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			CourseInvite expired = inviteExpiringAt(course, LocalDateTime.now().minusMinutes(1));
			given(courseInviteRepository.findByTokenAndDeletedAtIsNull(TOKEN)).willReturn(Optional.of(expired));

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_INVITE_EXPIRED.getMessage());
			then(courseRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("코스가 지워졌으면 COURSE_NOT_FOUND")
		void deletedCourse() {
			joiner();
			givenValidInvite(mockCourse(CourseType.CUSTOM, owner(), false));
			given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
		}

		@Test
		@DisplayName("큐레이션 코스는 토큰이 있어도 COURSE_FORBIDDEN — 2차 방어")
		void curatedCourse() {
			joiner();
			Course course = mockCourse(CourseType.CURATED, null, true);
			givenValidInvite(course);
			givenLockedCourse(course);

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
			then(courseMemberRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("방장이 자기 링크를 누르면 아무것도 안 하고 alreadyMember")
		void owner_alreadyMember() {
			Member courseOwner = owner();
			lenient().when(courseOwner.isGuest()).thenReturn(false);
			given(memberReader.getMember(OWNER_ID)).willReturn(courseOwner);
			Course course = mockCourse(CourseType.CUSTOM, courseOwner, true);
			givenValidInvite(course);
			givenLockedCourse(course);

			CourseInviteAcceptResponse response = courseMemberService.acceptInvite(OWNER_ID, TOKEN);

			assertThat(response.alreadyMember()).isTrue();
			then(courseMemberRepository).should(never()).save(any());
			then(course).should(never()).updateVisibility(any());
			then(courseRepository).should(never()).bumpVersion(any());
		}

		@Test
		@DisplayName("이미 활성 멤버면 아무것도 안 하고 alreadyMember")
		void existingMember_alreadyMember() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, true);

			CourseInviteAcceptResponse response = courseMemberService.acceptInvite(MEMBER_ID, TOKEN);

			assertThat(response.alreadyMember()).isTrue();
			then(courseMemberRepository).should(never()).save(any());
			then(savedCourseRepository).shouldHaveNoInteractions();
			then(courseRepository).should(never()).bumpVersion(any());
		}

		@Test
		@DisplayName("방장 포함 7명이면 마지막 한 자리로 수락된다 — 정원 경계")
		void seventhMember_accepted() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, false);
			givenOwnerRowExists(course);
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(7L);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.empty());
			given(savedCourseRepository.findByMemberIdAndCourseId(MEMBER_ID, COURSE_ID)).willReturn(Optional.empty());

			CourseInviteAcceptResponse response = courseMemberService.acceptInvite(MEMBER_ID, TOKEN);

			assertThat(response.alreadyMember()).isFalse();
			then(courseMemberRepository).should().save(any(CourseMember.class));
		}

		@Test
		@DisplayName("방장 포함 8명이면 COURSE_MEMBER_LIMIT")
		void full_throwsLimit() {
			joiner();
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenValidInvite(course);
			givenLockedCourse(course);
			givenActiveMember(MEMBER_ID, false);
			givenOwnerRowExists(course);
			given(courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(COURSE_ID)).willReturn(8L);

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_MEMBER_LIMIT.getMessage());
			then(courseMemberRepository).should(never()).save(any());
			then(course).should(never()).updateVisibility(any());
			then(courseRepository).should(never()).bumpVersion(any());
		}

		@Test
		@DisplayName("게스트는 참여할 수 없다 — 401 이 아니라 403 (앱이 401 을 받으면 게스트 세션을 재발급한다)")
		void guest_forbidden() {
			Member guest = mockMember(MEMBER_ID, "게스트");
			given(guest.isGuest()).willReturn(true);
			given(memberReader.getMember(MEMBER_ID)).willReturn(guest);

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_FORBIDDEN.getMessage());
			then(courseInviteRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("작성 제한(밴) 계정은 수락도 막힌다")
		void restricted_blocked() {
			willThrow(new MemberException(MemberErrorCode.MEMBER_WRITE_RESTRICTED))
				.given(memberRestrictionReader).validateCanWrite(MEMBER_ID);

			assertThatThrownBy(() -> courseMemberService.acceptInvite(MEMBER_ID, TOKEN))
				.isInstanceOf(MemberException.class);
			then(courseInviteRepository).shouldHaveNoInteractions();
		}
	}

	// ───────────────────────── leave / transferOwnership ─────────────────────────

	@Nested
	@DisplayName("leave")
	class Leave {

		private void givenCourse(Course course) {
			given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.of(course));
		}

		@Test
		@DisplayName("멤버는 자기 행만 닫는다 — 담았던 가게는 코스에 남고 방장은 그대로")
		void member_leavesOwnRow() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenCourse(course);
			CourseMember mine = row(course, MEMBER_ID);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.of(mine));

			courseMemberService.leave(MEMBER_ID, COURSE_ID);

			assertThat(mine.isDeleted()).isTrue();
			then(course).should(never()).changeOwner(any());
			then(courseRepository).should().bumpVersion(COURSE_ID);
			// 나간 사람의 메모는 지운다(확정). 가게는 남는다
			then(courseStoreMemoRepository).should().softDeleteAllByCourseIdAndMemberId(COURSE_ID, MEMBER_ID);
		}

		@Test
		@DisplayName("방장은 참여 순서가 가장 빠른 다른 멤버에게 넘기고 나간다. 새 방장의 행은 남는다")
		void owner_transfersToOldestMember() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenCourse(course);
			CourseMember ownerRow = row(course, OWNER_ID);
			CourseMember oldest = row(course, MEMBER_ID);
			CourseMember newer = row(course, OTHER_ID);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OWNER_ID))
				.willReturn(Optional.of(ownerRow));
			given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(COURSE_ID))
				.willReturn(List.of(ownerRow, oldest, newer));

			courseMemberService.leave(OWNER_ID, COURSE_ID);

			then(course).should().changeOwner(oldest.getMember());
			assertThat(ownerRow.isDeleted()).isTrue();
			assertThat(oldest.isDeleted()).isFalse();
			assertThat(newer.isDeleted()).isFalse();
			then(courseRepository).should().bumpVersion(COURSE_ID);
			then(courseStoreMemoRepository).should().softDeleteAllByCourseIdAndMemberId(COURSE_ID, OWNER_ID);
		}

		@Test
		@DisplayName("넘길 멤버가 없는 방장은 COURSE_NO_SUCCESSOR 이고 행도 닫히지 않는다")
		void owner_noSuccessor() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenCourse(course);
			CourseMember ownerRow = row(course, OWNER_ID);
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OWNER_ID))
				.willReturn(Optional.of(ownerRow));
			given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(COURSE_ID))
				.willReturn(List.of(ownerRow));

			assertThatThrownBy(() -> courseMemberService.leave(OWNER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_NO_SUCCESSOR.getMessage());
			assertThat(ownerRow.isDeleted()).isFalse();
			then(course).should(never()).changeOwner(any());
			then(courseRepository).should(never()).bumpVersion(any());
			then(courseStoreMemoRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("행이 없거나 이미 나간 사람은 COURSE_MEMBER_NOT_FOUND")
		void notMember() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			givenCourse(course);
			CourseMember left = row(course, OTHER_ID);
			left.leave();
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, MEMBER_ID))
				.willReturn(Optional.empty());
			given(courseMemberRepository.findByIdCourseIdAndIdMemberId(COURSE_ID, OTHER_ID))
				.willReturn(Optional.of(left));

			assertThatThrownBy(() -> courseMemberService.leave(MEMBER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_MEMBER_NOT_FOUND.getMessage());
			assertThatThrownBy(() -> courseMemberService.leave(OTHER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_MEMBER_NOT_FOUND.getMessage());
		}

		@Test
		@DisplayName("지운 코스면 COURSE_NOT_FOUND")
		void deletedCourse() {
			given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

			assertThatThrownBy(() -> courseMemberService.leave(MEMBER_ID, COURSE_ID))
				.isInstanceOf(CourseException.class)
				.hasMessageContaining(CourseErrorCode.COURSE_NOT_FOUND.getMessage());
		}
	}

	// ───────────────────────── leaveAllCourses (회원 탈퇴) ─────────────────────────

	@Nested
	@DisplayName("leaveAllCourses")
	class LeaveAllCourses {

		private void givenActiveRows(Long memberId, CourseMember... activeRows) {
			given(courseMemberRepository.findAllByIdMemberIdAndDeletedAtIsNullOrderByIdCourseIdAsc(memberId))
				.willReturn(List.of(activeRows));
		}

		@Test
		@DisplayName("방장이 탈퇴하면 코스 락을 잡고 참여 순서가 가장 빠른 멤버에게 넘긴 뒤 행·메모를 닫는다. 새 방장의 행은 남는다")
		void owner_transfersToOldestMember() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			CourseMember ownerRow = row(course, OWNER_ID);
			givenActiveRows(OWNER_ID, ownerRow);
			givenLockedCourse(course);
			CourseMember oldest = row(course, MEMBER_ID);
			given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(COURSE_ID))
				.willReturn(List.of(ownerRow, oldest));

			courseMemberService.leaveAllCourses(OWNER_ID);

			then(course).should().changeOwner(oldest.getMember());
			assertThat(ownerRow.isDeleted()).isTrue();
			assertThat(oldest.isDeleted()).isFalse();
			then(courseRepository).should().bumpVersion(COURSE_ID);
			then(courseStoreMemoRepository).should().softDeleteAllByCourseIdAndMemberId(COURSE_ID, OWNER_ID);
			then(courseInviteRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("넘길 멤버가 없는 방장은 행만 닫고 살아있는 초대 링크를 끈다 — 그 링크로 들어오면 탈퇴자가 방장으로 되살아난다")
		void owner_noSuccessor_closesRowAndRevokesInvite() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			CourseMember ownerRow = row(course, OWNER_ID);
			givenActiveRows(OWNER_ID, ownerRow);
			givenLockedCourse(course);
			given(courseMemberRepository.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(COURSE_ID))
				.willReturn(List.of(ownerRow));
			CourseInvite invite = inviteExpiringAt(course, LocalDateTime.now().plusDays(5));
			given(courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(COURSE_ID))
				.willReturn(Optional.of(invite));

			courseMemberService.leaveAllCourses(OWNER_ID);

			assertThat(ownerRow.isDeleted()).isTrue();
			assertThat(invite.isActive(LocalDateTime.now())).isFalse();
			then(course).should(never()).changeOwner(any());
			then(courseRepository).should(never()).bumpVersion(any());
			then(courseStoreMemoRepository).should().softDeleteAllByCourseIdAndMemberId(COURSE_ID, OWNER_ID);
		}

		@Test
		@DisplayName("멤버가 탈퇴하면 참여 중인 코스 전부에서 코스 id 순으로 락을 잡고 행을 닫고 메모를 지운다 — 담았던 가게는 남는다")
		void member_leavesEveryCourse() {
			Course first = mockCourse(CourseType.CUSTOM, owner(), false);
			Course second = mock(Course.class);
			Member secondOwner = owner();
			lenient().when(second.getId()).thenReturn(11L);
			lenient().when(second.getMember()).thenReturn(secondOwner);
			CourseMember firstRow = row(first, MEMBER_ID);
			CourseMember secondRow = row(second, MEMBER_ID);
			givenActiveRows(MEMBER_ID, firstRow, secondRow);
			givenLockedCourse(first);
			given(courseRepository.findWithLockByIdAndDeletedAtIsNull(11L)).willReturn(Optional.of(second));

			courseMemberService.leaveAllCourses(MEMBER_ID);

			assertThat(firstRow.isDeleted()).isTrue();
			assertThat(secondRow.isDeleted()).isTrue();
			// 행은 한 번에 읽는다 — 코스마다 멤버 행을 다시 읽지 않는다
			then(courseMemberRepository).should(never()).findByIdCourseIdAndIdMemberId(any(), any());
			then(first).should(never()).changeOwner(any());
			then(second).should(never()).changeOwner(any());
			then(courseRepository).should().bumpVersion(COURSE_ID);
			then(courseRepository).should().bumpVersion(11L);
			then(courseStoreMemoRepository).should().softDeleteAllByCourseIdAndMemberId(COURSE_ID, MEMBER_ID);
			then(courseStoreMemoRepository).should().softDeleteAllByCourseIdAndMemberId(11L, MEMBER_ID);
		}

		@Test
		@DisplayName("지운 코스의 행은 닫기만 한다 — 락 조회가 비어 있고, 승계도 version 도 메모 정리도 없다")
		void deletedCourse_closesRowOnly() {
			Course course = mockCourse(CourseType.CUSTOM, owner(), false);
			CourseMember ownerRow = row(course, OWNER_ID);
			givenActiveRows(OWNER_ID, ownerRow);
			given(courseRepository.findWithLockByIdAndDeletedAtIsNull(COURSE_ID)).willReturn(Optional.empty());

			courseMemberService.leaveAllCourses(OWNER_ID);

			assertThat(ownerRow.isDeleted()).isTrue();
			then(course).should(never()).changeOwner(any());
			then(courseRepository).should(never()).bumpVersion(any());
			then(courseStoreMemoRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("참여한 코스가 없으면 아무것도 하지 않는다")
		void noRows_doesNothing() {
			givenActiveRows(OTHER_ID);

			courseMemberService.leaveAllCourses(OTHER_ID);

			then(courseRepository).shouldHaveNoInteractions();
			then(courseStoreMemoRepository).shouldHaveNoInteractions();
		}
	}
}
