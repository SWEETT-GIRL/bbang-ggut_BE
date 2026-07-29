package sweetgirl.croissant.member.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
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
	private final SavedCourseRepository savedCourseRepository;
	private final SavedStoreRepository savedStoreRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MenuLikeRepository menuLikeRepository;
	private final LikedMenuCategoryRepository likedMenuCategoryRepository;
	private final ReviewRepository reviewRepository;
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
