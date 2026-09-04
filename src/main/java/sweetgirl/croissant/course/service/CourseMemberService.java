package sweetgirl.croissant.course.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.course.code.CourseErrorCode;
import sweetgirl.croissant.course.dto.response.CourseInviteAcceptResponse;
import sweetgirl.croissant.course.dto.response.CourseInvitePreviewResponse;
import sweetgirl.croissant.course.dto.response.CourseInvitePreviewResponse.Viewer;
import sweetgirl.croissant.course.dto.response.CourseInviteResponse;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.entity.CourseInvite;
import sweetgirl.croissant.course.entity.CourseMember;
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
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;

/**
 * 빵투어 동시편집의 초대와 멤버. 초대 링크 생성·미리보기·수락, 나가기, 코스 내 이름 변경, 방장 승계.
 *
 * 정원(방장 포함 8명)과 "코스당 살아있는 링크 1개"는 count 뒤 insert 라 락 없이는 동시 요청에 깨진다 —
 * 생성과 수락은 코스 행에 비관적 락을 잡고 진행한다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class CourseMemberService {

	/** 초대 링크 유효기간. */
	static final Duration INVITE_TTL = Duration.ofDays(7);

	/** 남은 기간이 이보다 짧으면 재사용하지 않고 재발급한다 — 보내는 링크는 항상 하루 이상 산다. */
	static final Duration INVITE_REUSE_MIN_REMAINING = Duration.ofDays(1);

	private final CourseRepository courseRepository;
	private final CourseMemberRepository courseMemberRepository;
	private final CourseInviteRepository courseInviteRepository;
	private final SavedCourseRepository savedCourseRepository;
	private final CourseStoreMemoRepository courseStoreMemoRepository;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;

	public CourseInviteResponse createInvite(Long memberId, Long courseId) {
		memberRestrictionReader.validateCanWrite(memberId);
		Member member = memberReader.getMember(memberId);
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		validateCanEdit(course, memberId);

		// 협업은 이 호출로 시작된다 — 방장도 코스 안에서 쓰는 이름을 갖는다. 행이 생기면 참여자 목록이 바뀐 것이다.
		if (ensureOwnerRow(course)) {
			courseRepository.bumpVersion(courseId);
		}

		LocalDateTime now = LocalDateTime.now();
		Optional<CourseInvite> current = courseInviteRepository
			.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(courseId);
		if (current.isPresent() && canReuse(current.get(), now)) {
			CourseInvite reusable = current.get();
			return new CourseInviteResponse(reusable.getToken(), reusable.getExpiresAt(), true);
		}

		// 코스당 살아있는 링크는 항상 1개 — 만료됐든 임박했든 이전 것은 무효화하고 새로 발급한다.
		current.ifPresent(CourseInvite::revoke);
		CourseInvite issued = CourseInvite.of(course, member, CollabTokenGenerator.generate(), now.plus(INVITE_TTL));
		courseInviteRepository.save(issued);
		return new CourseInviteResponse(issued.getToken(), issued.getExpiresAt(), false);
	}

	private boolean canReuse(CourseInvite invite, LocalDateTime now) {
		return invite.isActive(now) && !invite.getExpiresAt().isBefore(now.plus(INVITE_REUSE_MIN_REMAINING));
	}

	/** 비로그인 허용 — memberId 가 null 이면 viewer 를 채우지 않는다. 가게·메모는 내려주지 않는다. */
	@Transactional(readOnly = true)
	public CourseInvitePreviewResponse previewInvite(String token, Long memberId) {
		Optional<CourseInvite> found = courseInviteRepository.findByTokenAndDeletedAtIsNull(token);
		if (found.isEmpty()) {
			return CourseInvitePreviewResponse.invalid();
		}
		CourseInvite invite = found.get();
		Course course = invite.getCourse();
		long memberCount = courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(course.getId());
		CourseInviteState state = resolveState(invite, course, memberCount, LocalDateTime.now());
		Viewer viewer = memberId == null
			? null
			: new Viewer(!isOwner(course, memberId) && isActiveMember(course, memberId), isOwner(course, memberId));
		return CourseInvitePreviewResponse.of(state, course, memberCount, invite.getExpiresAt(), viewer);
	}

	private CourseInviteState resolveState(CourseInvite invite, Course course, long memberCount, LocalDateTime now) {
		// 방장이 탈퇴한 코스도 DELETED — 넘길 사람 없이 방장이 사라진 코스에 들어오게 두지 않는다
		if (course.isDeleted() || isOwnerDeleted(course)) {
			return CourseInviteState.DELETED;
		}
		if (invite.isExpired(now)) {
			return CourseInviteState.EXPIRED;
		}
		if (memberCount >= CourseMember.MEMBER_LIMIT) {
			return CourseInviteState.FULL;
		}
		return CourseInviteState.OK;
	}

	public CourseInviteAcceptResponse acceptInvite(Long memberId, String token, String displayName) {
		memberRestrictionReader.validateCanWrite(memberId);
		Member member = memberReader.getMember(memberId);
		// 게스트는 참여할 수 없다(로그인 필수). 401 을 쓰지 않는 이유: 앱의 apiClient 는 401 을 받으면
		// 게스트 세션을 자동 재발급하므로 같은 요청이 되풀이된다. 정상 경로는 앱이 로그인 게이트로 먼저 막는다.
		if (member.isGuest()) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}

		CourseInvite invite = courseInviteRepository.findByTokenAndDeletedAtIsNull(token)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_INVITE_INVALID));
		if (invite.isExpired(LocalDateTime.now())) {
			throw new CourseException(CourseErrorCode.COURSE_INVITE_EXPIRED);
		}
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(invite.getCourse().getId())
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		// 락을 기다리는 동안 방장이 링크를 재발급했을 수 있다 — 관리 중인 invite 인스턴스는 낡을 수 있으니 SQL 로 다시 본다
		courseInviteRepository.findByTokenAndDeletedAtIsNull(token)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_INVITE_INVALID));
		// 큐레이션 코스에는 초대가 생기지 않지만, 행이 심겨도 편집자가 늘지 않게 2차 방어.
		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		// 방장이 탈퇴한 코스 — 들어오면 ensureOwnerRow 가 탈퇴자를 방장으로 되살리고, 새 멤버는 삭제·양도 못 하는 코스에 갇힌다
		if (isOwnerDeleted(course)) {
			throw new CourseException(CourseErrorCode.COURSE_INVITE_INVALID);
		}
		// 자기 링크를 눌러보는 건 흔하다 — 에러가 아니라 "이미 있음"으로 200.
		if (isOwner(course, memberId) || isActiveMember(course, memberId)) {
			return new CourseInviteAcceptResponse(course.getId(), true);
		}

		// 정원에 방장이 들어가야 하므로 방장 행을 세기 전에 보장한다(정상 경로에선 createInvite 가 만들었다).
		ensureOwnerRow(course);
		if (courseMemberRepository.countByIdCourseIdAndDeletedAtIsNull(course.getId()) >= CourseMember.MEMBER_LIMIT) {
			throw new CourseException(CourseErrorCode.COURSE_MEMBER_LIMIT);
		}

		joinOrRejoin(course, member, displayName);
		// 멤버가 되면 찜은 모순이다 — 내 빵투어에 '함께' 배지로 뜬다.
		savedCourseRepository.findByMemberIdAndCourseId(memberId, course.getId())
			.ifPresent(savedCourseRepository::delete);
		// 협업 코스는 PUBLIC 일 수 없다(링크 공개는 된다). 전환 시점은 링크 생성이 아니라 첫 수락이다 — 링크만 만들고 아무도 안 오면 공개 그대로.
		if (course.isPublic()) {
			course.updateVisibility(CourseVisibility.PRIVATE);
		}
		courseRepository.bumpVersion(course.getId());
		return new CourseInviteAcceptResponse(course.getId(), false);
	}

	private void joinOrRejoin(Course course, Member member, String displayName) {
		// 나갔던 사람은 같은 PK 행을 되살린다 — 새 행이 아니다.
		courseMemberRepository.findByIdCourseIdAndIdMemberId(course.getId(), member.getId())
			.ifPresentOrElse(
				left -> left.rejoin(displayName),
				() -> courseMemberRepository.save(CourseMember.of(course, member, displayName)));
	}

	/**
	 * 방장 행이 없으면 만든다. 이름 기본값은 닉네임(길이 제한에 맞춰 자른다).
	 *
	 * @return 행을 새로 만들거나 되살렸으면 true
	 */
	private boolean ensureOwnerRow(Course course) {
		Member owner = course.getMember();
		// 탈퇴한 방장의 행은 되살리지 않는다 — 활성 방장으로 다시 보이면 안 된다
		if (owner == null || owner.isDeleted()) {
			return false;
		}
		Optional<CourseMember> existing = courseMemberRepository
			.findByIdCourseIdAndIdMemberId(course.getId(), owner.getId());
		if (existing.isEmpty()) {
			courseMemberRepository.save(CourseMember.of(course, owner, defaultDisplayName(owner)));
			return true;
		}
		if (existing.get().isDeleted()) {
			existing.get().rejoin(defaultDisplayName(owner));
			return true;
		}
		return false;
	}

	static String defaultDisplayName(Member member) {
		String nickname = member.getNickname() == null ? "" : member.getNickname().strip();
		if (nickname.isEmpty()) {
			return "방장";
		}
		return nickname.length() > CourseMember.DISPLAY_NAME_MAX_LENGTH
			? nickname.substring(0, CourseMember.DISPLAY_NAME_MAX_LENGTH)
			: nickname;
	}

	/**
	 * 멤버는 그냥 나간다. 방장은 승계하고 나간다. 담았던 가게는 코스에 남고, 쓴 메모는 지운다.
	 * 코스 락으로 시작한다 — 방장과 후임이 동시에 나가면 둘 다 서로를 보고 진행해 떠난 사람이 방장으로 남는다.
	 */
	public void leave(Long memberId, Long courseId) {
		Course course = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		CourseMember me = getActiveMemberRow(courseId, memberId);
		if (isOwner(course, memberId)) {
			transferOwnership(course, memberId);
		} else {
			me.leave();
			courseRepository.bumpVersion(courseId);
		}
		// 나간 사람의 메모는 삭제(확정). 탈퇴한 멤버·방장도 같은 규칙이다
		courseStoreMemoRepository.softDeleteAllByCourseIdAndMemberId(courseId, memberId);
	}

	/**
	 * 방장을 참여 순서가 가장 빠른 다른 멤버에게 넘기고, 넘긴 사람의 행은 닫는다.
	 * 나가기와 회원 탈퇴가 같이 쓴다. 새 방장의 행은 남긴다 — 방장도 코스 안에서 이름을 갖는다.
	 */
	public void transferOwnership(Course course, Long fromMemberId) {
		if (!transferOwnershipIfPossible(course, fromMemberId)) {
			throw new CourseException(CourseErrorCode.COURSE_NO_SUCCESSOR);
		}
	}

	/** @return 넘길 멤버가 있어 승계했으면 true. 없으면 아무것도 하지 않고 false */
	private boolean transferOwnershipIfPossible(Course course, Long fromMemberId) {
		List<CourseMember> members = courseMemberRepository
			.findAllByIdCourseIdAndDeletedAtIsNullOrderByJoinedAtAsc(course.getId());
		Optional<CourseMember> successor = members.stream()
			.filter(row -> !row.getId().getMemberId().equals(fromMemberId))
			.findFirst();
		if (successor.isEmpty()) {
			return false;
		}
		course.changeOwner(successor.get().getMember());
		members.stream()
			.filter(row -> row.getId().getMemberId().equals(fromMemberId))
			.findFirst()
			.ifPresent(CourseMember::leave);
		courseRepository.bumpVersion(course.getId());
		return true;
	}

	/**
	 * 회원 탈퇴 — 참여 중인 협업 코스 전부에서 나간다. 나가기와 같은 규칙이다: 방장이면 승계하고,
	 * 담았던 가게는 코스에 남기고, 쓴 메모는 지운다. 만든 초대 토큰은 코스의 것이라 그대로 둔다.
	 * 넘길 멤버가 없는 방장은 예외 대신 행만 닫고 살아있는 초대 링크를 끈다 — 탈퇴는 이미 진행 중이라 나가기처럼 막을 수
	 * 없고, 코스는 탈퇴자 소유로 남는다. course_members 행이 없는 소유 코스(협업 전 코스)는 손대지 않는다.
	 * 코스 id 오름차순으로 락을 잡는다 — 교차 소유한 두 사람의 탈퇴가 겹쳐도 락 순서가 같아 데드락이 없다.
	 */
	public void leaveAllCourses(Long memberId) {
		for (Long courseId : courseMemberRepository.findActiveCourseIdsByMemberId(memberId)) {
			Optional<CourseMember> activeRow = courseMemberRepository.findByIdCourseIdAndIdMemberId(courseId, memberId)
				.filter(row -> !row.isDeleted());
			if (activeRow.isEmpty()) {
				continue;
			}
			CourseMember row = activeRow.get();
			Optional<Course> locked = courseRepository.findWithLockByIdAndDeletedAtIsNull(courseId);
			if (locked.isEmpty()) {
				// 지운 코스 — 행만 닫는다. 승계·version·메모 정리는 의미 없다
				row.leave();
				continue;
			}
			Course course = locked.get();
			if (isOwner(course, memberId)) {
				if (!transferOwnershipIfPossible(course, memberId)) {
					row.leave();
					// 넘길 사람 없이 방장이 사라진 코스 — 살아있는 링크로 누가 들어오면 탈퇴자가 방장으로 되살아나므로 링크를 끈다
					revokeActiveInvite(courseId);
				}
			} else {
				row.leave();
				courseRepository.bumpVersion(courseId);
			}
			courseStoreMemoRepository.softDeleteAllByCourseIdAndMemberId(courseId, memberId);
		}
	}

	private void revokeActiveInvite(Long courseId) {
		courseInviteRepository.findFirstByCourseIdAndDeletedAtIsNullOrderByCreatedAtDesc(courseId)
			.ifPresent(CourseInvite::revoke);
	}

	private boolean isOwnerDeleted(Course course) {
		return course.getMember() != null && course.getMember().isDeleted();
	}

	public void rename(Long memberId, Long courseId, String displayName) {
		courseRepository.findByIdAndDeletedAtIsNull(courseId)
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_NOT_FOUND));
		getActiveMemberRow(courseId, memberId).rename(displayName);
		courseRepository.bumpVersion(courseId);
	}

	private CourseMember getActiveMemberRow(Long courseId, Long memberId) {
		return courseMemberRepository.findByIdCourseIdAndIdMemberId(courseId, memberId)
			.filter(row -> !row.isDeleted())
			.orElseThrow(() -> new CourseException(CourseErrorCode.COURSE_MEMBER_NOT_FOUND));
	}

	/** CourseCommandService.validateCanEdit 와 같은 판정 — CURATED 검사가 소유자 판정보다 먼저다. */
	private void validateCanEdit(Course course, Long memberId) {
		if (course.getType() == CourseType.CURATED) {
			throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
		}
		if (isOwner(course, memberId) || isActiveMember(course, memberId)) {
			return;
		}
		throw new CourseException(CourseErrorCode.COURSE_FORBIDDEN);
	}

	private boolean isOwner(Course course, Long memberId) {
		return course.getMember() != null && course.getMember().getId().equals(memberId);
	}

	private boolean isActiveMember(Course course, Long memberId) {
		return courseMemberRepository.existsByIdCourseIdAndIdMemberIdAndDeletedAtIsNull(course.getId(), memberId);
	}
}
