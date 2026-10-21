package sweetgirl.croissant.reward.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.reward.dto.response.MyShopResponse;
import sweetgirl.croissant.reward.dto.response.ShopCatalogResponse;
import sweetgirl.croissant.reward.entity.MemberCharacterLook;
import sweetgirl.croissant.reward.repository.MemberCharacterLookRepository;
import sweetgirl.croissant.reward.repository.MemberShopItemRepository;
import sweetgirl.croissant.reward.repository.ShopCatalogRepository;
import sweetgirl.croissant.reward.repository.ShopItemRepository;
import sweetgirl.croissant.world.repository.MemberWorldRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShopQueryService {

	private final ShopItemRepository shopItemRepository;
	private final ShopCatalogRepository shopCatalogRepository;
	private final MemberShopItemRepository memberShopItemRepository;
	private final MemberCharacterLookRepository memberCharacterLookRepository;
	private final MemberWorldRepository memberWorldRepository;
	private final MemberReader memberReader;

	private final AtomicReference<ShopCatalogResponse> cachedCatalog = new AtomicReference<>();

	/**
	 * 파는 품목. 만든 응답을 들고 있다가 버전이 같으면 그대로 돌려준다 — 세계관 카탈로그와 같은 방식이다.
	 * 버전 한 칸은 매번 읽어, 다른 인스턴스에서 어드민이 바꾼 것도 따라간다.
	 */
	public ShopCatalogResponse getCatalog() {
		int version = currentVersion();
		ShopCatalogResponse cached = cachedCatalog.get();
		if (cached != null && cached.version() == version) {
			return cached;
		}
		ShopCatalogResponse loaded = new ShopCatalogResponse(version,
			shopItemRepository.findAllByActiveTrueOrderBySortOrderAscCodeAsc().stream()
				.map(ShopCatalogResponse.Item::from)
				.toList());
		cachedCatalog.set(loaded);
		return loaded;
	}

	public int currentVersion() {
		return shopCatalogRepository.findCurrentVersion().orElse(1);
	}

	/** 내가 산 것과 지금 쓰는 것. 게스트는 아무것도 없다. */
	public MyShopResponse getMyShop(Long memberId) {
		if (memberReader.getMember(memberId).isGuest()) {
			return new MyShopResponse(List.of(), Map.of(), null);
		}
		Map<String, String> looks = new LinkedHashMap<>();
		for (MemberCharacterLook look : memberCharacterLookRepository.findAllByMemberId(memberId)) {
			looks.put(look.getCharacterCode(), look.getItemCode());
		}
		return new MyShopResponse(
			memberShopItemRepository.findAllByMemberIdOrderByIdAsc(memberId).stream()
				.map(owned -> new MyShopResponse.Owned(owned.characterCodeOrNull(), owned.getItemCode()))
				.toList(),
			looks,
			memberWorldRepository.findHomePropItem(memberId).orElse(null));
	}
}
