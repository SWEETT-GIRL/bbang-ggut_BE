package sweetgirl.croissant.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.badge.repository.MemberBadgeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.StorePlanRepository;
import sweetgirl.croissant.course.repository.CourseInviteRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.mission.repository.MemberMissionRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

/**
 * 게스트로 쌓은 데이터를 로그인한 회원 계정으로 옮긴다.
 *
 * 게스트 계정을 그대로 승격(promote)할 수 있는 건 "이 소셜 계정으로 처음 가입"할 때뿐이다.
 * 이미 회원인 사람이 게스트로 쓰다가 로그인하거나, 탈퇴 후 재가입해서 새 Member가 만들어지면
 * 승격 대상이 없어 게스트가 만든 코스·찜이 그대로 남아 사라진 것처럼 보인다. 그 경우 이 서비스가
 * 소유자만 바꿔 붙인다.
 *
 * (member_id, X) 유니크가 걸린 테이블은 회원이 이미 가진 행과 충돌하므로, 게스트 쪽 중복을 먼저
 * 지우고 남은 것만 옮긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestDataMigrationService {

	private final CourseRepository courseRepository;
	private final CourseMemberRepository courseMemberRepository;
	private final CourseInviteRepository courseInviteRepository;
	private final CourseStoreMemoRepository courseStoreMemoRepository;
	private final SavedCourseRepository savedCourseRepository;
	private final SavedStoreRepository savedStoreRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MenuLikeRepository menuLikeRepository;
	private final LikedMenuCategoryRepository likedMenuCategoryRepository;
	private final ReviewRepository reviewRepository;
	private final BreadRecordRepository breadRecordRepository;
	private final StorePlanRepository storePlanRepository;
	private final CourseProgressRepository courseProgressRepository;
	private final MemberBadgeRepository memberBadgeRepository;
	private final MemberMissionRepository memberMissionRepository;
	private final MenuRepository menuRepository;
	private final CourseProgressService courseProgressService;
	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;

	/**
	 * 게스트 데이터를 회원에게 이관하고 게스트 계정을 닫는다.
	 *
	 * @return 옮긴 코스 수 (로그·검증용)
	 */
	@Transactional
	public int migrate(Long guestId, Long targetId) {
		if (guestId == null || targetId == null || guestId.equals(targetId)) {
			return 0;
		}

		int courses = courseRepository.moveOwner(guestId, targetId);

		// 협업 코스 — 게스트는 멤버가 될 수 없지만 방장은 될 수 있다. 방장 행과 만든 초대 토큰이
		// 게스트 id 에 남으면 로그인 뒤 협업이 끊긴다. (course_id, member_id) PK 라 중복 정리가 먼저다.
		courseMemberRepository.deleteGuestDuplicates(guestId, targetId);
		courseMemberRepository.moveOwner(guestId, targetId);
		courseInviteRepository.moveOwner(guestId, targetId);
		// 게스트가 자기 코스에 쓴 메모는 이관된 course_stores.memo 에서 온 것이라 여기 안 옮기면 로그인 뒤 사라진다
		courseStoreMemoRepository.deleteGuestDuplicates(guestId, targetId);
		courseStoreMemoRepository.moveOwner(guestId, targetId);

		savedCourseRepository.deleteGuestDuplicates(guestId, targetId);
		savedCourseRepository.moveOwner(guestId, targetId);

		savedStoreRepository.deleteGuestDuplicates(guestId, targetId);
		savedStoreRepository.moveOwner(guestId, targetId);

		subscribedStoreRepository.deleteGuestDuplicates(guestId, targetId);
		subscribedStoreRepository.moveOwner(guestId, targetId);

		menuLikeRepository.deleteGuestDuplicates(guestId, targetId);
		menuLikeRepository.moveOwner(guestId, targetId);

		likedMenuCategoryRepository.deleteGuestDuplicates(guestId, targetId);
		likedMenuCategoryRepository.moveOwner(guestId, targetId);

		reviewRepository.moveOwner(guestId, targetId);
		breadRecordRepository.moveOwner(guestId, targetId);

		storePlanRepository.deleteGuestDuplicates(guestId, targetId);
		storePlanRepository.moveOwner(guestId, targetId);

		courseProgressRepository.mergeGuestDuplicates(guestId, targetId);
		courseProgressRepository.deleteGuestDuplicates(guestId, targetId);
		courseProgressRepository.moveOwner(guestId, targetId);

		memberBadgeRepository.mergeGuestDuplicates(guestId, targetId);
		memberBadgeRepository.deleteGuestDuplicates(guestId, targetId);
		memberBadgeRepository.moveOwner(guestId, targetId);

		memberMissionRepository.mergeGuestDuplicates(guestId, targetId);
		memberMissionRepository.deleteGuestDuplicates(guestId, targetId);
		memberMissionRepository.moveOwner(guestId, targetId);

		menuRepository.moveCreator(guestId, targetId);
		courseProgressService.refreshForMember(targetId);

		closeGuest(guestId);

		log.info("[GuestMigration] 게스트 데이터 이관 완료 - guestId={}, targetId={}, courses={}",
			guestId, targetId, courses);
		return courses;
	}

	/**
	 * 빈 껍데기가 된 게스트 계정을 닫는다. 소프트 삭제해야 다음 로그인에서 이 계정이 다시
	 * 승격/이관 후보로 잡히지 않는다. 남은 refresh token도 함께 정리한다.
	 */
	private void closeGuest(Long guestId) {
		refreshTokenRepository.findAllByMember(memberRepository.getReferenceById(guestId))
			.forEach(refreshTokenRepository::delete);
		memberRepository.findById(guestId).ifPresent(Member::softDelete);
	}
}
