package sweetgirl.croissant.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.dto.response.ProfileResponse;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.world.dto.response.WorldCatalogResponse;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberWorld;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.entity.WorldCharacterAsset;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;
import sweetgirl.croissant.world.service.WorldCatalogService;
import sweetgirl.croissant.world.service.WorldLevel;
import sweetgirl.croissant.world.service.WorldLevelReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileQueryService")
class ProfileQueryServiceTest {

	private static final Long VIEWER = 1L;
	private static final Long TARGET = 2L;

	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private MemberFollowRepository memberFollowRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private MemberTitleReader memberTitleReader;
	@Mock
	private MemberWorldRepository memberWorldRepository;
	@Mock
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private WorldCharacterAssetRepository worldCharacterAssetRepository;
	@Mock
	private WorldLevelReader worldLevelReader;
	@Mock
	private WorldCatalogService worldCatalogService;

	@Mock
	private sweetgirl.croissant.abuse.service.ReportedTargetReader reportedTargetReader;

	@InjectMocks
	private ProfileQueryService profileQueryService;

	private Member member(Long id, String nickname) {
		Member member = Member.builder().nickname(nickname).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private void givenWorld(Long memberId, String characterCode) {
		MemberWorld world = mock(MemberWorld.class);
		given(world.getSelectedCharacter()).willReturn(characterCode);
		given(memberWorldRepository.findById(memberId)).willReturn(Optional.of(world));
		WorldCharacter character = mock(WorldCharacter.class);
		given(character.getCode()).willReturn(characterCode);
		given(worldCharacterRepository.findById(characterCode)).willReturn(Optional.of(character));
		WorldCharacterAsset face = mock(WorldCharacterAsset.class);
		given(face.getImageKey()).willReturn("world/" + characterCode + "/front.png");
		given(worldCharacterAssetRepository.findById(new WorldCharacterAssetId(characterCode, "front")))
			.willReturn(Optional.of(face));
		given(memberWorldRepository.findHomePropItem(memberId)).willReturn(Optional.of("lamp"));
		given(worldLevelReader.currentLevel(memberId)).willReturn(new WorldLevel(3, 10, 20, "mars", null));
		given(worldCatalogService.getCatalog()).willReturn(new WorldCatalogResponse(1, List.of(
			new WorldPlanetResponse("mars", "화성", null, 1, 10, null, true, 0, 0, false, null, List.of())), List.of()));
	}

	@Test
	@DisplayName("남의 프로필: 캐릭터 얼굴 · 소품 · 레벨 · 행성 이름 · 칭호 · 공개 빵로그 수 · 팔로우 수 · 관계")
	void othersProfile() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET, "빵순이")));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(false);
		given(memberRestrictionReader.isProfileRestricted(TARGET)).willReturn(false);
		givenWorld(TARGET, "dog");
		given(memberTitleReader.titleOf(TARGET)).willReturn(Optional.of("성수동 빵순이"));
		given(breadRecordRepository.countPublicByMemberId(TARGET)).willReturn(12L);
		given(memberFollowRepository.countByIdFolloweeId(TARGET)).willReturn(30L);
		given(memberFollowRepository.countByIdFollowerId(TARGET)).willReturn(7L);
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(VIEWER, TARGET)).willReturn(true);
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(TARGET, VIEWER)).willReturn(false);

		ProfileResponse response = profileQueryService.getProfile(VIEWER, TARGET);

		assertThat(response.blocked()).isFalse();
		assertThat(response.isMe()).isFalse();
		assertThat(response.nickname()).isEqualTo("빵순이");
		assertThat(response.characterCode()).isEqualTo("dog");
		assertThat(response.characterImageKey()).isEqualTo("world/dog/front.png");
		assertThat(response.homePropItem()).isEqualTo("lamp");
		assertThat(response.level()).isEqualTo(3);
		assertThat(response.planetId()).isEqualTo("mars");
		assertThat(response.planetName()).isEqualTo("화성");
		assertThat(response.title()).isEqualTo("성수동 빵순이");
		assertThat(response.publicRecordCount()).isEqualTo(12);
		assertThat(response.followerCount()).isEqualTo(30);
		assertThat(response.followingCount()).isEqualTo(7);
		assertThat(response.followedByMe()).isTrue();
		assertThat(response.followsMe()).isFalse();
	}

	@Test
	@DisplayName("차단 관계거나 프로필 제한 중이면 blocked 만 참이고 나머지는 비어 있다")
	void blocked() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET, "빵순이")));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(true);

		assertThat(profileQueryService.getProfile(VIEWER, TARGET)).isEqualTo(ProfileResponse.blocked(TARGET));

		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(false);
		given(memberRestrictionReader.isProfileRestricted(TARGET)).willReturn(true);
		assertThat(profileQueryService.getProfile(VIEWER, TARGET).blocked()).isTrue();
		then(worldLevelReader).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("내 프로필은 제한 중이어도 보이고 관계는 false, 세계관이 없으면 마스코트")
	void myProfile() {
		given(socialMemberReader.findSocial(VIEWER)).willReturn(Optional.of(member(VIEWER, "나")));
		given(memberWorldRepository.findById(VIEWER)).willReturn(Optional.empty());
		WorldCharacter mascot = mock(WorldCharacter.class);
		given(mascot.getCode()).willReturn("bbangdaeng");
		given(worldCharacterRepository.findByMascotTrue()).willReturn(Optional.of(mascot));
		given(worldCharacterAssetRepository.findById(new WorldCharacterAssetId("bbangdaeng", "front")))
			.willReturn(Optional.empty());
		given(memberWorldRepository.findHomePropItem(VIEWER)).willReturn(Optional.empty());
		given(worldLevelReader.currentLevel(VIEWER)).willReturn(new WorldLevel(1, 0, 5, "earth", null));
		given(worldCatalogService.getCatalog()).willReturn(new WorldCatalogResponse(1, List.of(), List.of()));
		given(memberTitleReader.titleOf(VIEWER)).willReturn(Optional.empty());

		ProfileResponse response = profileQueryService.getProfile(VIEWER, VIEWER);

		assertThat(response.isMe()).isTrue();
		assertThat(response.characterCode()).isEqualTo("bbangdaeng");
		assertThat(response.characterImageKey()).isNull();
		assertThat(response.planetName()).isNull();
		assertThat(response.title()).isNull();
		assertThat(response.followedByMe()).isFalse();
		then(memberBlockRepository).shouldHaveNoInteractions();
		then(memberRestrictionReader).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("상대가 게스트 · 봇 · 탈퇴면 PROFILE404_MEMBER")
	void notFound() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.empty());

		assertThatThrownBy(() -> profileQueryService.getProfile(VIEWER, TARGET))
			.isInstanceOf(ProfileException.class)
			.extracting("errorCode").isEqualTo(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND);
	}
}
