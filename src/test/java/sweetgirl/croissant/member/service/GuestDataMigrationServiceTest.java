package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import sweetgirl.croissant.badge.repository.MemberBadgeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
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
import sweetgirl.croissant.mission.repository.MemberMissionRepository;
import sweetgirl.croissant.personalization.entity.UserTasteProfile;
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

@ExtendWith(MockitoExtension.class)
class GuestDataMigrationServiceTest {

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseMemberRepository courseMemberRepository;
	@Mock
	private CourseInviteRepository courseInviteRepository;
	@Mock
	private CourseStoreMemoRepository courseStoreMemoRepository;
	@Mock
	private SavedCourseRepository savedCourseRepository;
	@Mock
	private SavedStoreRepository savedStoreRepository;
	@Mock
	private SubscribedStoreRepository subscribedStoreRepository;
	@Mock
	private MenuLikeRepository menuLikeRepository;
	@Mock
	private LikedMenuCategoryRepository likedMenuCategoryRepository;
	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private StorePlanRepository storePlanRepository;
	@Mock
	private CourseProgressRepository courseProgressRepository;
	@Mock
	private MemberBadgeRepository memberBadgeRepository;
	@Mock
	private MemberMissionRepository memberMissionRepository;
	@Mock
	private MenuRepository menuRepository;
	@Mock
	private CourseProgressService courseProgressService;
	@Mock
	private MemberRepository memberRepository;
	@Mock
	private RefreshTokenRepository refreshTokenRepository;
	@Mock
	private CourseStoreVisitRepository courseStoreVisitRepository;
	@Mock
	private RequestLogRepository requestLogRepository;
	@Mock
	private RecommendationRepository recommendationRepository;
	@Mock
	private UserStoreInteractionRepository userStoreInteractionRepository;
	@Mock
	private UserTasteProfileRepository userTasteProfileRepository;
	@Mock
	private MemberWorldRepository memberWorldRepository;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private MemberPlanetArrivalRepository memberPlanetArrivalRepository;
	@Mock
	private MemberWakeTicketRepository memberWakeTicketRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;

	@InjectMocks
	private GuestDataMigrationService guestDataMigrationService;

	private static final Long GUEST_ID = 10L;
	private static final Long TARGET_ID = 20L;

	// 게스트가 만든 것들이 전부 회원에게 넘어가야 한다 — 유니크 걸린 건 중복 제거가 선행되어야 한다
	@Test
	void movesEveryGuestOwnedRow_andRemovesDuplicatesFirst() {
		Member guest = mock(Member.class);
		given(courseRepository.moveOwner(GUEST_ID, TARGET_ID)).willReturn(3);
		given(memberRepository.getReferenceById(GUEST_ID)).willReturn(guest);
		given(refreshTokenRepository.findAllByMember(guest)).willReturn(List.of());
		given(memberRepository.findById(GUEST_ID)).willReturn(Optional.of(guest));

		int moved = guestDataMigrationService.migrate(GUEST_ID, TARGET_ID);

		assertThat(moved).isEqualTo(3);

		var order = inOrder(savedCourseRepository, savedStoreRepository, subscribedStoreRepository,
			menuLikeRepository, likedMenuCategoryRepository);
		order.verify(savedCourseRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		order.verify(savedCourseRepository).moveOwner(GUEST_ID, TARGET_ID);
		order.verify(savedStoreRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		order.verify(savedStoreRepository).moveOwner(GUEST_ID, TARGET_ID);
		order.verify(subscribedStoreRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		order.verify(subscribedStoreRepository).moveOwner(GUEST_ID, TARGET_ID);
		order.verify(menuLikeRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		order.verify(menuLikeRepository).moveOwner(GUEST_ID, TARGET_ID);
		order.verify(likedMenuCategoryRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		order.verify(likedMenuCategoryRepository).moveOwner(GUEST_ID, TARGET_ID);

		then(reviewRepository).should().moveOwner(GUEST_ID, TARGET_ID);
		then(breadRecordRepository).should().moveOwner(GUEST_ID, TARGET_ID);
		var breadLogOrder = inOrder(storePlanRepository, courseProgressRepository);
		breadLogOrder.verify(storePlanRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		breadLogOrder.verify(storePlanRepository).moveOwner(GUEST_ID, TARGET_ID);
		breadLogOrder.verify(courseProgressRepository).mergeGuestDuplicates(GUEST_ID, TARGET_ID);
		breadLogOrder.verify(courseProgressRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		breadLogOrder.verify(courseProgressRepository).moveOwner(GUEST_ID, TARGET_ID);
		// 세계관 — 빠뜨리면 게스트로 만난 첫 친구와 진행도가 로그인하는 순간 사라진다
		var worldOrder = inOrder(memberWorldRepository, memberCharacterRepository,
			memberPlanetArrivalRepository);
		worldOrder.verify(memberWorldRepository).mergeGuestDuplicates(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberWorldRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberWorldRepository).moveOwner(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberCharacterRepository).mergeGuestDuplicates(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberCharacterRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberCharacterRepository).moveOwner(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberPlanetArrivalRepository).mergeGuestDuplicates(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberPlanetArrivalRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		worldOrder.verify(memberPlanetArrivalRepository).moveOwner(GUEST_ID, TARGET_ID);
		then(memberWakeTicketRepository).should().moveOwner(GUEST_ID, TARGET_ID);

		var badgeOrder = inOrder(memberBadgeRepository);
		badgeOrder.verify(memberBadgeRepository).mergeGuestDuplicates(GUEST_ID, TARGET_ID);
		badgeOrder.verify(memberBadgeRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		badgeOrder.verify(memberBadgeRepository).moveOwner(GUEST_ID, TARGET_ID);
		then(menuRepository).should().moveCreator(GUEST_ID, TARGET_ID);
		then(courseProgressService).should().refreshForMember(TARGET_ID);
		// 협업 코스: 게스트가 방장으로 시작한 코스의 멤버 행·초대 토큰도 따라가야 한다
		var collabOrder = inOrder(courseMemberRepository);
		collabOrder.verify(courseMemberRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		collabOrder.verify(courseMemberRepository).moveOwner(GUEST_ID, TARGET_ID);
		then(courseInviteRepository).should().moveOwner(GUEST_ID, TARGET_ID);
		var memoOrder = inOrder(courseStoreMemoRepository);
		memoOrder.verify(courseStoreMemoRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		memoOrder.verify(courseStoreMemoRepository).moveOwner(GUEST_ID, TARGET_ID);
		// 방문 체크도 코스를 따라간다 — 진행률과 사용자 유형(DOER)의 재료
		var visitOrder = inOrder(courseStoreVisitRepository, courseProgressService);
		visitOrder.verify(courseStoreVisitRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		visitOrder.verify(courseStoreVisitRepository).moveOwner(GUEST_ID, TARGET_ID);
		visitOrder.verify(courseProgressService).refreshForMember(TARGET_ID);
		// 04:00 적재가 회원 id 로 읽는 표도 옮긴다 — 안 옮기면 병합 직전 행동이 닫힌 게스트로 적재된다(#663 ④)
		then(requestLogRepository).should().moveOwner(GUEST_ID, TARGET_ID);
		then(recommendationRepository).should().moveOwner(GUEST_ID, TARGET_ID);
		// 취향 신호: 회원과 겹치는 사건은 지우고 옮긴 뒤, 게스트 프로필은 버리고 커밋 뒤 회원 재계산 이벤트를 낸다(#663 ④)
		var tasteOrder = inOrder(userStoreInteractionRepository, userTasteProfileRepository, eventPublisher);
		tasteOrder.verify(userStoreInteractionRepository).deleteGuestDuplicates(GUEST_ID, TARGET_ID);
		tasteOrder.verify(userStoreInteractionRepository).moveOwner(GUEST_ID, TARGET_ID);
		tasteOrder.verify(userTasteProfileRepository).findById(GUEST_ID);
		tasteOrder.verify(eventPublisher).publishEvent(new GuestMergedEvent(GUEST_ID, TARGET_ID));
	}

	// 게스트 프로필 행은 PK 가 member_id 라 옮길 수 없다 — 있으면 지우고 커밋 뒤 회원 쪽을 다시 계산하게 한다
	@Test
	void discardsGuestTasteProfile_beforeRecomputingTarget() {
		Member guest = mock(Member.class);
		UserTasteProfile guestProfile = UserTasteProfile.createEmpty(GUEST_ID, LocalDateTime.of(2026, 9, 18, 4, 0));
		given(memberRepository.getReferenceById(GUEST_ID)).willReturn(guest);
		given(refreshTokenRepository.findAllByMember(guest)).willReturn(List.of());
		given(memberRepository.findById(GUEST_ID)).willReturn(Optional.of(guest));
		given(userTasteProfileRepository.findById(GUEST_ID)).willReturn(Optional.of(guestProfile));

		guestDataMigrationService.migrate(GUEST_ID, TARGET_ID);

		var order = inOrder(userTasteProfileRepository, eventPublisher);
		order.verify(userTasteProfileRepository).delete(guestProfile);
		order.verify(eventPublisher).publishEvent(new GuestMergedEvent(GUEST_ID, TARGET_ID));
	}

	// 빈 껍데기 게스트가 남아 다음 로그인에서 다시 이관 대상이 되면 안 된다
	@Test
	void closesGuestAccount_afterMigration() {
		Member guest = mock(Member.class);
		given(memberRepository.getReferenceById(GUEST_ID)).willReturn(guest);
		given(refreshTokenRepository.findAllByMember(guest)).willReturn(List.of());
		given(memberRepository.findById(GUEST_ID)).willReturn(Optional.of(guest));

		guestDataMigrationService.migrate(GUEST_ID, TARGET_ID);

		then(guest).should().softDelete();
	}

	// 승격으로 이미 같은 계정이 된 경우 — 자기 자신에게 옮기면 데이터가 지워질 수 있어 반드시 막아야 한다
	@Test
	void doesNothing_whenGuestAndTargetAreSame() {
		int moved = guestDataMigrationService.migrate(GUEST_ID, GUEST_ID);

		assertThat(moved).isZero();
		then(courseRepository).shouldHaveNoInteractions();
		then(courseMemberRepository).shouldHaveNoInteractions();
		then(courseInviteRepository).shouldHaveNoInteractions();
		then(courseStoreMemoRepository).shouldHaveNoInteractions();
		then(savedStoreRepository).shouldHaveNoInteractions();
		then(memberRepository).shouldHaveNoInteractions();
	}
}
