package sweetgirl.croissant.reward.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.code.RewardErrorCode;
import sweetgirl.croissant.reward.dto.request.ShopPurchaseRequest;
import sweetgirl.croissant.reward.dto.response.RewardItemResponse;
import sweetgirl.croissant.reward.dto.response.ShopPurchaseResponse;
import sweetgirl.croissant.reward.entity.MemberCharacterLook;
import sweetgirl.croissant.reward.entity.MemberCharacterLookId;
import sweetgirl.croissant.reward.entity.MemberShopItem;
import sweetgirl.croissant.reward.entity.ShopItem;
import sweetgirl.croissant.reward.enums.CrumbReason;
import sweetgirl.croissant.reward.exception.RewardException;
import sweetgirl.croissant.reward.repository.MemberCharacterLookRepository;
import sweetgirl.croissant.reward.repository.MemberShopItemRepository;
import sweetgirl.croissant.reward.repository.ShopItemRepository;
import sweetgirl.croissant.world.code.WorldErrorCode;
import sweetgirl.croissant.world.entity.MemberCharacter;
import sweetgirl.croissant.world.entity.MemberCharacterId;
import sweetgirl.croissant.world.entity.WorldCharacterAssetId;
import sweetgirl.croissant.world.exception.WorldException;
import sweetgirl.croissant.world.repository.MemberCharacterRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.service.WorldLevelReader;

/**
 * 상점 — 부스러기로 친구의 모습과 홈 소품을 산다.
 *
 * <p>구매는 한 트랜잭션이다: 산 것으로 남기고(이미 가졌으면 여기서 멈춘다) → 값을 치르고(모자라면 전부 되돌린다) →
 * 모습이면 바로 입힌다. 가격은 요청이 아니라 품목 표의 값이다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ShopCommandService {

	private final ShopItemRepository shopItemRepository;
	private final MemberShopItemRepository memberShopItemRepository;
	private final MemberCharacterLookRepository memberCharacterLookRepository;
	private final MemberCharacterRepository memberCharacterRepository;
	private final WorldCharacterAssetRepository worldCharacterAssetRepository;
	private final MemberWorldRepository memberWorldRepository;
	private final WorldLevelReader worldLevelReader;
	private final CrumbGrantService crumbGrantService;
	private final MemberReader memberReader;

	public ShopPurchaseResponse purchase(Long memberId, ShopPurchaseRequest request) {
		memberReader.requireLoggedIn(memberId);
		ShopItem item = shopItemRepository.findById(request.itemCode())
			.filter(ShopItem::isActive)
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_SHOP_ITEM_NOT_FOUND));
		String characterCode = item.isPose() ? requireFriendFor(memberId, item, request.characterCode())
			: MemberShopItem.NO_CHARACTER;
		if (item.getLockLevel() != null && worldLevelReader.currentLevel(memberId).level() < item.getLockLevel()) {
			throw new RewardException(RewardErrorCode.REWARD_SHOP_LEVEL_LOCKED);
		}

		if (memberShopItemRepository.insertIfAbsent(memberId, characterCode, item.getCode(),
			LocalDateTime.now()) == 0) {
			throw new RewardException(RewardErrorCode.REWARD_SHOP_ALREADY_OWNED);
		}
		String key = "shop:" + (item.isPose() ? characterCode : "-") + ":" + item.getCode();
		SpendResult spent = crumbGrantService.spend(memberId, CrumbReason.SHOP_PURCHASE, item.getPrice(), key);
		if (item.isPose()) {
			wear(memberId, characterCode, item.getCode());
		}
		return new ShopPurchaseResponse(spent.to(),
			List.of(RewardItemResponse.spent(spent, CrumbReason.SHOP_PURCHASE)));
	}

	/** 친구에게 가진 모습을 입힌다. {@code itemCode} 가 없으면 기본 모습으로 돌아간다. */
	public void changeLook(Long memberId, String characterCode, String itemCode) {
		memberReader.requireLoggedIn(memberId);
		if (itemCode == null) {
			memberCharacterLookRepository.deleteById(new MemberCharacterLookId(memberId, characterCode));
			return;
		}
		ShopItem item = shopItemRepository.findById(itemCode)
			.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_SHOP_ITEM_NOT_FOUND));
		if (!item.isPose()
			|| !memberShopItemRepository.existsByMemberIdAndCharacterCodeAndItemCode(memberId, characterCode,
				itemCode)) {
			throw new RewardException(RewardErrorCode.REWARD_SHOP_ITEM_NOT_OWNED);
		}
		wear(memberId, characterCode, itemCode);
	}

	/** 홈에 가진 소품을 놓는다. {@code itemCode} 가 없으면 치운다. */
	public void changeHomeProp(Long memberId, String itemCode) {
		memberReader.requireLoggedIn(memberId);
		if (itemCode != null) {
			ShopItem item = shopItemRepository.findById(itemCode)
				.orElseThrow(() -> new RewardException(RewardErrorCode.REWARD_SHOP_ITEM_NOT_FOUND));
			if (item.isPose() || !memberShopItemRepository.existsByMemberIdAndCharacterCodeAndItemCode(
				memberId, MemberShopItem.NO_CHARACTER, itemCode)) {
				throw new RewardException(RewardErrorCode.REWARD_SHOP_ITEM_NOT_OWNED);
			}
		}
		if (memberWorldRepository.updateHomePropItem(memberId, itemCode) == 0) {
			throw new WorldException(WorldErrorCode.WORLD_NOT_STARTED);
		}
	}

	/** 모습은 깨어 있는 내 친구에게만, 그 친구에게 그 포즈 그림이 있을 때만 산다. */
	private String requireFriendFor(Long memberId, ShopItem item, String characterCode) {
		if (characterCode == null || characterCode.isBlank()) {
			throw new RewardException(RewardErrorCode.REWARD_SHOP_CHARACTER_REQUIRED);
		}
		boolean awake = memberCharacterRepository.findById(new MemberCharacterId(memberId, characterCode))
			.map(MemberCharacter::isAwake)
			.orElse(false);
		if (!awake) {
			throw new WorldException(WorldErrorCode.WORLD_CHARACTER_LOCKED);
		}
		if (!worldCharacterAssetRepository.existsById(new WorldCharacterAssetId(characterCode, item.getPose()))) {
			throw new RewardException(RewardErrorCode.REWARD_SHOP_POSE_UNAVAILABLE);
		}
		return characterCode;
	}

	private void wear(Long memberId, String characterCode, String itemCode) {
		memberCharacterLookRepository.findById(new MemberCharacterLookId(memberId, characterCode))
			.ifPresentOrElse(
				look -> look.wear(itemCode),
				() -> memberCharacterLookRepository.save(MemberCharacterLook.of(memberId, characterCode, itemCode)));
	}
}
