package sweetgirl.croissant.reward.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.request.ShopPurchaseRequest;
import sweetgirl.croissant.reward.dto.response.ShopPurchaseResponse;
import sweetgirl.croissant.reward.entity.MemberCharacterLook;
import sweetgirl.croissant.reward.entity.MemberCharacterLookId;
import sweetgirl.croissant.reward.entity.ShopItem;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.enums.ShopItemKind;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.MemberCharacterLookRepository;
import sweetgirl.croissant.reward.repository.MemberShopItemRepository;
import sweetgirl.croissant.reward.repository.ShopItemRepository;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.enums.AcquiredBy;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.service.WorldLevel;
import sweetgirl.croissant.world.service.WorldLevelReader;

@ExtendWith(MockitoExtension.class)
class ShopCommandServiceTest {

	private static final Long MEMBER_ID = 7L;

	@Mock
	private ShopItemRepository shopItemRepository;
	@Mock
	private MemberShopItemRepository memberShopItemRepository;
	@Mock
	private MemberCharacterLookRepository memberCharacterLookRepository;
	@Mock
	private MemberCharacterRepository memberCharacterRepository;
	@Mock
	private WorldCharacterAssetRepository worldCharacterAssetRepository;
	@Mock
	private MemberWorldRepository memberWorldRepository;
	@Mock
	private WorldLevelReader worldLevelReader;
	@Mock
	private CrumbGrantService crumbGrantService;
	@Mock
	private MemberReader memberReader;

	@InjectMocks
	private ShopCommandService shopCommandService;

	private static ShopItem pose(String code, String pose, int price, Integer lockLevel) {
		ShopItem item = ShopItem.create(code, ShopItemKind.POSE);
		item.revise(pose, "이름", price, lockLevel, 10, false, true);
		return item;
	}

	private static ShopItem prop(String code, int price) {
		ShopItem item = ShopItem.create(code, ShopItemKind.PROP);
		item.revise(null, "이름", price, null, 110, false, true);
		return item;
	}

	private void givenAwakeFriend(String characterCode) {
		given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, characterCode)))
			.willReturn(Optional.of(MemberCharacter.awake(MEMBER_ID, characterCode, AcquiredBy.FIRST, null)));
	}

	@Nested
	@DisplayName("구매")
	class Purchase {

		@Test
		@DisplayName("모습을 사면 표의 가격만큼 빠지고, 그 친구에게 바로 입혀진다")
		void buysPose() {
			given(shopItemRepository.findById("pose-joy")).willReturn(Optional.of(pose("pose-joy", "joy", 90, null)));
			givenAwakeFriend("dog");
			given(worldCharacterAssetRepository.existsById(new WorldCharacterAssetId("dog", "joy"))).willReturn(true);
			given(memberShopItemRepository.insertIfAbsent(eq(MEMBER_ID), eq("dog"), eq("pose-joy"), any()))
				.willReturn(1);
			given(crumbGrantService.spend(7L, CrumbReason.SHOP_PURCHASE, 90, "shop:dog:pose-joy"))
				.willReturn(new SpendResult(true, 90, 200, 110));
			given(memberCharacterLookRepository.findById(new MemberCharacterLookId(MEMBER_ID, "dog")))
				.willReturn(Optional.empty());

			ShopPurchaseResponse response = shopCommandService.purchase(
				MEMBER_ID, new ShopPurchaseRequest("pose-joy", "dog"));

			assertThat(response.crumbs()).isEqualTo(110);
			assertThat(response.rewards()).singleElement().satisfies(reward -> {
				assertThat(reward.amount()).isEqualTo(-90);
				assertThat(reward.from()).isEqualTo(200);
				assertThat(reward.to()).isEqualTo(110);
				assertThat(reward.reason()).isEqualTo(CrumbReason.SHOP_PURCHASE);
			});
			then(memberCharacterLookRepository).should().save(any(MemberCharacterLook.class));
		}

		@Test
		@DisplayName("소품은 친구 없이 사고, 입히는 일도 없다")
		void buysProp() {
			given(shopItemRepository.findById("prop-flag")).willReturn(Optional.of(prop("prop-flag", 50)));
			given(memberShopItemRepository.insertIfAbsent(eq(MEMBER_ID), eq(""), eq("prop-flag"), any()))
				.willReturn(1);
			given(crumbGrantService.spend(7L, CrumbReason.SHOP_PURCHASE, 50, "shop:-:prop-flag"))
				.willReturn(new SpendResult(true, 50, 60, 10));

			assertThat(shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("prop-flag", null)).crumbs())
				.isEqualTo(10);
			then(memberCharacterLookRepository).shouldHaveNoInteractions();
			then(memberCharacterRepository).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("이미 가진 품목이면 REWARD409_2 이고 값을 치르지 않는다")
		void alreadyOwned() {
			given(shopItemRepository.findById("prop-flag")).willReturn(Optional.of(prop("prop-flag", 50)));
			given(memberShopItemRepository.insertIfAbsent(eq(MEMBER_ID), eq(""), eq("prop-flag"), any()))
				.willReturn(0);

			assertThatThrownBy(() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("prop-flag", null)))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ALREADY_OWNED);
			then(crumbGrantService).shouldHaveNoInteractions();
		}

		@Test
		@DisplayName("부스러기가 모자라면 REWARD409_1 이 그대로 올라가 구매 전체가 되돌려진다")
		void insufficient() {
			given(shopItemRepository.findById("prop-flag")).willReturn(Optional.of(prop("prop-flag", 50)));
			given(memberShopItemRepository.insertIfAbsent(eq(MEMBER_ID), eq(""), eq("prop-flag"), any()))
				.willReturn(1);
			willThrow(new RewardException(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT))
				.given(crumbGrantService).spend(anyLong(), any(), anyInt(), anyString());

			assertThatThrownBy(() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("prop-flag", null)))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_CRUMBS_INSUFFICIENT);
		}

		@Test
		@DisplayName("그 친구에게 그 포즈 그림이 없으면 REWARD400_2")
		void poseUnavailable() {
			given(shopItemRepository.findById("pose-joy")).willReturn(Optional.of(pose("pose-joy", "joy", 90, null)));
			givenAwakeFriend("frog");
			given(worldCharacterAssetRepository.existsById(new WorldCharacterAssetId("frog", "joy"))).willReturn(false);

			assertThatThrownBy(
				() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("pose-joy", "frog")))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_POSE_UNAVAILABLE);
			then(memberShopItemRepository).should(never()).insertIfAbsent(anyLong(), anyString(), anyString(), any());
		}

		@Test
		@DisplayName("레벨이 모자라면 REWARD403_1")
		void levelLocked() {
			given(shopItemRepository.findById("pose-bread-tower"))
				.willReturn(Optional.of(pose("pose-bread-tower", "bread-tower", 180, 5)));
			givenAwakeFriend("dog");
			given(worldCharacterAssetRepository.existsById(new WorldCharacterAssetId("dog", "bread-tower")))
				.willReturn(true);
			given(worldLevelReader.currentLevel(MEMBER_ID)).willReturn(new WorldLevel(4, 20, "mars", "village"));

			assertThatThrownBy(() -> shopCommandService.purchase(
				MEMBER_ID, new ShopPurchaseRequest("pose-bread-tower", "dog")))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_LEVEL_LOCKED);
		}

		@Test
		@DisplayName("모습을 사는데 친구를 안 골랐으면 REWARD400_4, 아직 못 깨운 친구면 WORLD403_1")
		void needsAwakeFriend() {
			given(shopItemRepository.findById("pose-joy")).willReturn(Optional.of(pose("pose-joy", "joy", 90, null)));
			given(memberCharacterRepository.findById(new MemberCharacterId(MEMBER_ID, "cat")))
				.willReturn(Optional.empty());

			assertThatThrownBy(() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("pose-joy", null)))
				.isInstanceOf(RewardException.class)
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_CHARACTER_REQUIRED);
			assertThatThrownBy(() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("pose-joy", "cat")))
				.isInstanceOf(WorldException.class)
				.extracting("errorCode").isEqualTo(WorldErrorCode.WORLD_CHARACTER_LOCKED);
		}

		@Test
		@DisplayName("없는 품목과 끈 품목은 REWARD404_3")
		void notOnSale() {
			ShopItem off = prop("prop-old", 50);
			off.revise(null, "이름", 50, null, 110, false, false);
			given(shopItemRepository.findById("prop-old")).willReturn(Optional.of(off));
			given(shopItemRepository.findById("nope")).willReturn(Optional.empty());

			assertThatThrownBy(() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("prop-old", null)))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ITEM_NOT_FOUND);
			assertThatThrownBy(() -> shopCommandService.purchase(MEMBER_ID, new ShopPurchaseRequest("nope", null)))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ITEM_NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("입히기 · 놓기")
	class Wear {

		@Test
		@DisplayName("가진 모습으로 바꾼다")
		void changesLook() {
			given(shopItemRepository.findById("pose-joy")).willReturn(Optional.of(pose("pose-joy", "joy", 90, null)));
			given(memberShopItemRepository.existsByMemberIdAndCharacterCodeAndItemCode(MEMBER_ID, "dog", "pose-joy"))
				.willReturn(true);
			MemberCharacterLook look = MemberCharacterLook.of(MEMBER_ID, "dog", "pose-music");
			given(memberCharacterLookRepository.findById(new MemberCharacterLookId(MEMBER_ID, "dog")))
				.willReturn(Optional.of(look));

			shopCommandService.changeLook(MEMBER_ID, "dog", "pose-joy");

			assertThat(look.getItemCode()).isEqualTo("pose-joy");
		}

		@Test
		@DisplayName("다른 친구에게 산 모습은 이 친구에게 입힐 수 없다 — REWARD400_3")
		void lookNotOwnedForThisFriend() {
			given(shopItemRepository.findById("pose-joy")).willReturn(Optional.of(pose("pose-joy", "joy", 90, null)));
			given(memberShopItemRepository.existsByMemberIdAndCharacterCodeAndItemCode(MEMBER_ID, "cat", "pose-joy"))
				.willReturn(false);

			assertThatThrownBy(() -> shopCommandService.changeLook(MEMBER_ID, "cat", "pose-joy"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ITEM_NOT_OWNED);
		}

		@Test
		@DisplayName("모습을 비워 보내면 기본 모습으로 돌아간다")
		void backToDefaultLook() {
			shopCommandService.changeLook(MEMBER_ID, "dog", null);

			then(memberCharacterLookRepository).should().deleteById(new MemberCharacterLookId(MEMBER_ID, "dog"));
		}

		@Test
		@DisplayName("가진 소품을 홈에 놓고, 비워 보내면 치운다")
		void changesHomeProp() {
			given(shopItemRepository.findById("prop-flag")).willReturn(Optional.of(prop("prop-flag", 50)));
			given(memberShopItemRepository.existsByMemberIdAndCharacterCodeAndItemCode(MEMBER_ID, "", "prop-flag"))
				.willReturn(true);
			given(memberWorldRepository.updateHomePropItem(MEMBER_ID, "prop-flag")).willReturn(1);
			given(memberWorldRepository.updateHomePropItem(MEMBER_ID, null)).willReturn(1);

			shopCommandService.changeHomeProp(MEMBER_ID, "prop-flag");
			shopCommandService.changeHomeProp(MEMBER_ID, null);

			then(memberWorldRepository).should().updateHomePropItem(MEMBER_ID, "prop-flag");
			then(memberWorldRepository).should().updateHomePropItem(MEMBER_ID, null);
		}

		@Test
		@DisplayName("안 가진 소품과 모습 품목은 홈에 놓을 수 없다")
		void propNotOwned() {
			given(shopItemRepository.findById("prop-flag")).willReturn(Optional.of(prop("prop-flag", 50)));
			given(shopItemRepository.findById("pose-joy")).willReturn(Optional.of(mock(ShopItem.class)));
			given(shopItemRepository.findById("pose-joy").get().isPose()).willReturn(true);

			assertThatThrownBy(() -> shopCommandService.changeHomeProp(MEMBER_ID, "prop-flag"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ITEM_NOT_OWNED);
			assertThatThrownBy(() -> shopCommandService.changeHomeProp(MEMBER_ID, "pose-joy"))
				.extracting("errorCode").isEqualTo(RewardErrorCode.REWARD_SHOP_ITEM_NOT_OWNED);
			then(memberWorldRepository).shouldHaveNoInteractions();
		}
	}
}
