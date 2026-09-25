package sweetgirl.croissant.world.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.service.NicknameDrawService;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.dto.response.WorldPlanetResponse;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.WorldFixtures;
import sweetgirl.croissant.world.enums.CharacterStatus;
import sweetgirl.croissant.world.enums.PlanetGroup;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.RewardedAdTransactionRepository;

/**
 * 적립은 이 콜백에서만 한다. 앱이 "봤어요"라고 말하는 것으로는 아무 일도 일어나지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class RewardedAdSsvServiceTest {

	private static final String QUERY = "ad_network=5450213213286189855&ad_unit=ca-app-pub-1%2F2"
		+ "&reward_amount=1&reward_item=friend&transaction_id=TX1&custom_data=7%3Afrog"
		+ "&signature=SIG&key_id=1234567890";

	@Mock
	private RewardedAdSignatureVerifier signatureVerifier;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private WorldCatalogService worldCatalogService;
	@Mock
	private WorldCommandService worldCommandService;
	@Mock
	private RewardedAdTransactionRepository rewardedAdTransactionRepository;
	@Mock
	private NicknameDrawService nicknameDrawService;

	@InjectMocks
	private RewardedAdSsvService rewardedAdSsvService;

	private static final WorldPlanetResponse MARS = new WorldPlanetResponse(
		"mars", "화성", PlanetGroup.SOLAR, 2, 30, "earth", false, 1, 2, true, null, List.of());

	private void givenVerifiedAndWakeable() {
		given(signatureVerifier.verify(QUERY)).willReturn(true);
		given(worldCommandService.requireWakeable(7L, "frog")).willReturn(MARS);
		given(rewardedAdTransactionRepository.recordIfFirstTime(
			any(), any(), any(), any(), any(), any())).willReturn(1);
	}

	@Test
	@DisplayName("서명이 맞지 않으면 아무것도 하지 않는다")
	void ignoresBadSignature() {
		given(signatureVerifier.verify(QUERY)).willReturn(false);

		assertThat(rewardedAdSsvService.credit(QUERY)).isFalse();
		then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
	}

	@Test
	@DisplayName("custom_data 가 없으면 적립할 곳을 몰라 버린다")
	void ignoresMissingCustomData() {
		String query = "ad_network=5&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);

		assertThat(rewardedAdSsvService.credit(query)).isFalse();
		then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
	}

	@Test
	@DisplayName("아직 깨울 수 없는 친구면 적립하지 않는다 — custom_data 를 바꿔도 규칙을 넘지 못한다")
	void respectsWakeRules() {
		given(signatureVerifier.verify(QUERY)).willReturn(true);
		willThrow(new WorldException(WorldErrorCode.WORLD_ROULETTE_REQUIRED))
			.given(worldCommandService).requireWakeable(7L, "frog");

		assertThat(rewardedAdSsvService.credit(QUERY)).isFalse();
		then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
	}

	@Test
	@DisplayName("한 편 적립하고, 아직 모자라면 깨우지 않는다")
	void countsWithoutAwakening() {
		givenVerifiedAndWakeable();
		given(memberCharacterRepository.addAdView(7L, "frog")).willReturn(1);
		given(memberCharacterRepository.findById(new MemberCharacterId(7L, "frog")))
			.willReturn(Optional.of(WorldFixtures.memberCharacter(
				7L, "frog", CharacterStatus.SLEEPING, 1, null)));

		assertThat(rewardedAdSsvService.credit(QUERY)).isTrue();
		then(memberCharacterRepository).should(never()).awakeIfSleeping(any(), anyString(), anyString());
	}

	@Test
	@DisplayName("필요한 편수를 채우면 깨운다 — 편수는 그 친구가 사는 행성 값이다")
	void awakensWhenEnough() {
		givenVerifiedAndWakeable();
		given(memberCharacterRepository.addAdView(7L, "frog")).willReturn(1);
		given(memberCharacterRepository.findById(new MemberCharacterId(7L, "frog")))
			.willReturn(Optional.of(WorldFixtures.memberCharacter(
				7L, "frog", CharacterStatus.SLEEPING, 2, null)));

		assertThat(rewardedAdSsvService.credit(QUERY)).isTrue();
		then(memberCharacterRepository).should().awakeIfSleeping(7L, "frog", "AD");
	}

	@Test
	@DisplayName("같은 보상이 다시 오면 버린다 — 구글은 콜백을 재시도한다")
	void ignoresRetriedCallback() {
		given(signatureVerifier.verify(QUERY)).willReturn(true);
		given(worldCommandService.requireWakeable(7L, "frog")).willReturn(MARS);
		given(rewardedAdTransactionRepository.recordIfFirstTime(
			any(), any(), any(), any(), any(), any())).willReturn(0);

		assertThat(rewardedAdSsvService.credit(QUERY)).isFalse();
		then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
	}

	@Test
	@DisplayName("transaction_id 가 없으면 중복을 가릴 수 없어 버린다")
	void ignoresMissingTransactionId() {
		String query = "custom_data=7%3Afrog&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);
		given(worldCommandService.requireWakeable(7L, "frog")).willReturn(MARS);

		assertThat(rewardedAdSsvService.credit(query)).isFalse();
		then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
	}

	@Test
	@DisplayName("정산에 대조할 수 있게 광고 단위와 보상 내용을 함께 남긴다")
	void recordsAdUnitForReconciliation() {
		givenVerifiedAndWakeable();
		given(memberCharacterRepository.addAdView(7L, "frog")).willReturn(1);
		given(memberCharacterRepository.findById(new MemberCharacterId(7L, "frog")))
			.willReturn(Optional.of(WorldFixtures.memberCharacter(
				7L, "frog", CharacterStatus.SLEEPING, 1, null)));

		rewardedAdSsvService.credit(QUERY);

		then(rewardedAdTransactionRepository).should()
			.recordIfFirstTime("TX1", 7L, "frog", "ca-app-pub-1/2", 1, "friend");
	}

	@Test
	@DisplayName("이미 깨어난 친구면 적립하지 않는다")
	void ignoresAwakeCharacter() {
		givenVerifiedAndWakeable();
		given(memberCharacterRepository.addAdView(7L, "frog")).willReturn(0);

		assertThat(rewardedAdSsvService.credit(QUERY)).isFalse();
	}

	@Test
	@DisplayName("닉네임 광고(nickname:회원번호)는 친구가 아니라 닉네임 뽑기로 보낸다")
	void routesNicknameAdToNicknameDraw() {
		String query = "transaction_id=TX2&custom_data=nickname%3A7&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);
		given(rewardedAdTransactionRepository.recordNicknameIfFirstTime(
			any(), any(), any(), any(), any())).willReturn(1);
		given(nicknameDrawService.drawWithAd(7L)).willReturn(true);

		assertThat(rewardedAdSsvService.credit(query)).isTrue();
		then(worldCommandService).should(never()).requireWakeable(any(), anyString());
		then(memberCharacterRepository).should(never()).addAdView(any(), anyString());
	}

	@Test
	@DisplayName("닉네임 광고의 회원 번호가 숫자가 아니면 버린다")
	void ignoresBadNicknameMemberId() {
		String query = "transaction_id=TX2&custom_data=nickname%3Aabc&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);

		assertThat(rewardedAdSsvService.credit(query)).isFalse();
		then(nicknameDrawService).should(never()).drawWithAd(any());
	}

	@Test
	@DisplayName("닉네임 광고도 이력 표에 남긴다 — 정산 대조를 친구 광고와 한 표에서 한다")
	void recordsNicknameAd() {
		String query = "ad_unit=ca-app-pub-1%2F3&reward_amount=1&reward_item=nickname"
			+ "&transaction_id=TX2&custom_data=nickname%3A7&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);
		given(rewardedAdTransactionRepository.recordNicknameIfFirstTime(
			any(), any(), any(), any(), any())).willReturn(1);
		given(nicknameDrawService.drawWithAd(7L)).willReturn(true);

		rewardedAdSsvService.credit(query);

		then(rewardedAdTransactionRepository).should()
			.recordNicknameIfFirstTime("TX2", 7L, "ca-app-pub-1/3", 1, "nickname");
		then(rewardedAdTransactionRepository).should(never())
			.recordIfFirstTime(any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("같은 닉네임 광고 보상이 다시 오면 뽑지 않는다 — 구글은 콜백을 재시도한다")
	void ignoresRetriedNicknameCallback() {
		String query = "transaction_id=TX2&custom_data=nickname%3A7&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);
		given(rewardedAdTransactionRepository.recordNicknameIfFirstTime(
			any(), any(), any(), any(), any())).willReturn(0);

		assertThat(rewardedAdSsvService.credit(query)).isFalse();
		then(nicknameDrawService).should(never()).drawWithAd(any());
	}

	@Test
	@DisplayName("닉네임 광고에 transaction_id 가 없으면 중복을 가릴 수 없어 뽑지 않는다")
	void ignoresNicknameAdWithoutTransactionId() {
		String query = "custom_data=nickname%3A7&signature=SIG&key_id=1";
		given(signatureVerifier.verify(query)).willReturn(true);

		assertThat(rewardedAdSsvService.credit(query)).isFalse();
		then(nicknameDrawService).should(never()).drawWithAd(any());
	}
}
