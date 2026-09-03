package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.badge.repository.MemberBadgeRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.StorePlanRepository;
import sweetgirl.croissant.course.repository.CourseInviteRepository;
import sweetgirl.croissant.course.repository.CourseMemberRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
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

@ExtendWith(MockitoExtension.class)
class GuestDataMigrationServiceTest {

	@Mock
	private CourseRepository courseRepository;
	@Mock
	private CourseMemberRepository courseMemberRepository;
	@Mock
	private CourseInviteRepository courseInviteRepository;
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
		then(savedStoreRepository).shouldHaveNoInteractions();
		then(memberRepository).shouldHaveNoInteractions();
	}
}
