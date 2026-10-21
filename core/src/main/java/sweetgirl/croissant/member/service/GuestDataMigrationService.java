package sweetgirl.croissant.member.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.MemberTutorialCompletionRepository;
import sweetgirl.croissant.breadlog.repository.StorePlanRepository;
import sweetgirl.croissant.course.repository.CourseInviteRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreMemoRepository;
import sweetgirl.croissant.course.repository.CourseStoreVisitRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.course.service.CourseProgressService;
import sweetgirl.croissant.global.logging.repository.RequestLogRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.event.GuestMergedEvent;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.personalization.repository.UserStoreInteractionRepository;
import sweetgirl.croissant.personalization.repository.UserTasteProfileRepository;
import sweetgirl.croissant.recommendation.repository.RecommendationRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.repository.SavedStoreRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberPlanetArrivalRepository;
import sweetgirl.croissant.world.repository.MemberWakeTicketRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;

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
	private final MemberTutorialCompletionRepository tutorialCompletions;

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
	private final MenuRepository menuRepository;
	private final CourseProgressService courseProgressService;
	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final CourseStoreVisitRepository courseStoreVisitRepository;
	private final RequestLogRepository requestLogRepository;
	private final RecommendationRepository recommendationRepository;
	private final UserStoreInteractionRepository userStoreInteractionRepository;
	private final UserTasteProfileRepository userTasteProfileRepository;
	private final MemberWorldRepository memberWorldRepository;
	private final MemberCharacterRepository memberCharacterRepository;
	private final MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	private final MemberWakeTicketRepository memberWakeTicketRepository;
	private final ApplicationEventPublisher eventPublisher;

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

		// 완료 저장과 같은 회원 잠금. 늘 ID 순으로 잡고 닫힌 게스트를 다시 이관하지 않는다.
		var first = memberRepository.findActiveForUpdate(Math.min(guestId, targetId));
		var second = memberRepository.findActiveForUpdate(Math.max(guestId, targetId));
		if (first.isEmpty() || second.isEmpty()) {
			return 0;
		}
		Member source = guestId < targetId ? first.get() : second.get();
		if (!source.isGuest()) {
			return 0;
		}
		breadRecordRepository.retireDuplicateTutorial(guestId, targetId);
		tutorialCompletions.removeMergedCompletion(guestId, targetId);
		tutorialCompletions.moveOwner(guestId, targetId);

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

		// 방문 체크는 코스를 따라가야 한다 — 진행률(refreshForMember)과 사용자 유형(DOER)의 재료다
		courseStoreVisitRepository.deleteGuestDuplicates(guestId, targetId);
		courseStoreVisitRepository.moveOwner(guestId, targetId);

		// 세계관 — 빠뜨리면 게스트로 만난 첫 친구와 모아둔 광고 진행도가 로그인하는 순간 사라진다.
		// 진행도(max_record_count)와 도착 이력이 없으면 행성도 처음으로 되돌아간다.
		memberWorldRepository.mergeGuestDuplicates(guestId, targetId);
		memberWorldRepository.deleteGuestDuplicates(guestId, targetId);
		memberWorldRepository.moveOwner(guestId, targetId);

		memberCharacterRepository.mergeGuestDuplicates(guestId, targetId);
		memberCharacterRepository.deleteGuestDuplicates(guestId, targetId);
		memberCharacterRepository.moveOwner(guestId, targetId);

		memberPlanetArrivalRepository.mergeGuestDuplicates(guestId, targetId);
		memberPlanetArrivalRepository.deleteGuestDuplicates(guestId, targetId);
		memberPlanetArrivalRepository.moveOwner(guestId, targetId);

		memberWakeTicketRepository.moveOwner(guestId, targetId);

		menuRepository.moveCreator(guestId, targetId);

		// 취향 신호(#663 ④): 게스트 시절 저장·조회·담기 신호를 회원에게 붙이고, 게스트 프로필은 버린다 — PK 가 member_id 라 프로필
		// 행은 옮길 수 없다. 회원 프로필 재계산은 커밋 뒤 이벤트로(GuestMergedEventListener): 가게 프로필 데이터 문제가 로그인 병합을
		// 되돌리지 않게. 카드의 칩 상태는 옮겨진 찜·담기를 실시간으로 세므로 재계산 전에도 게스트 시절 행동을 반영한다.
		// 04:00 적재는 조회를 request_log.member_id 로, 추천 반응을 recommendation.member_id 로 읽는다. 옮기지 않으면 병합
		// 직전 게스트 행동이 닫힌 계정으로 적재되어 회원에게 영영 안 붙고, 인기도(#663 ③)가 한 사람을 둘로 센다.
		requestLogRepository.moveOwner(guestId, targetId);
		recommendationRepository.moveOwner(guestId, targetId);
		userStoreInteractionRepository.deleteGuestDuplicates(guestId, targetId);
		userStoreInteractionRepository.moveOwner(guestId, targetId);
		userTasteProfileRepository.findById(guestId).ifPresent(userTasteProfileRepository::delete);
		eventPublisher.publishEvent(new GuestMergedEvent(guestId, targetId));

		// 옮겨 온 방문으로 완주가 된 빵투어를 마감한다. 위의 벌크 이관 쿼리는 영속성 컨텍스트를 비우므로(clearAutomatically)
		// 엔티티를 고치는 일은 전부 그 뒤에 와야 한다 — 앞에 두면 완주 표시가 예외 없이 버려진다.
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
