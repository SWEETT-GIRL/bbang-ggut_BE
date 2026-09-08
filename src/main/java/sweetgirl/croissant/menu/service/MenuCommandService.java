package sweetgirl.croissant.menu.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.dto.request.MenuCreateRequest;
import sweetgirl.croissant.menu.dto.response.MenuRegistrationResult;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuLike;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class MenuCommandService {

	private final MenuRepository menuRepository;
	private final MenuLikeRepository menuLikeRepository;
	private final MemberRepository memberRepository;
	private final MemberReader memberReader;
	private final MemberRestrictionReader memberRestrictionReader;
	private final StoreRepository storeRepository;
	private final MenuNameClassificationRepository menuNameClassificationRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MenuKindClassifier menuKindClassifier;

	public MenuRegistrationResult registerMenu(Long storeId, Long memberId, MenuCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		Store store = storeRepository.findByIdForUpdate(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		String name = request.name().strip();
		String normalizedName = MenuNameNormalizer.normalize(name);
		return menuRepository.findFirstByStoreIdAndNormalizedNameAndDeletedAtIsNullOrderByIdAsc(storeId, normalizedName)
			.map(existing -> new MenuRegistrationResult(existing.getId(), false, null))
			.orElseGet(() -> createMemberMenu(store, member, name, normalizedName));
	}

	private MenuRegistrationResult createMemberMenu(Store store, Member member, String name, String normalizedName) {
		Menu menu = menuRepository.save(Menu.builder()
			.store(store)
			.name(name)
			.createdByMember(member)
			.build());

		// 같은 이름을 스케줄러가 이미 3회 일치로 판정했으면 그대로 쓴다 — 같은 이름은 같은 kind
		Optional<MenuKindVerdict> cached = menuNameClassificationRepository.findById(normalizedName)
			.map(MenuNameClassification::toVerdict);
		if (cached.isPresent()) {
			MenuKindVerdict verdict = cached.get();
			if (verdict.decided()) {
				menuCategoryRepository.findByName(verdict.kind())
					.ifPresent(category -> menu.applyClassification(category, verdict.bakeryItem()));
			}
			// 갈렸던 이름(decided=false)은 다시 묻지 않는다 — 제안 없이
			String suggestion = verdict.decided() && verdict.bakeryItem() ? verdict.kind().name() : null;
			return new MenuRegistrationResult(menu.getId(), true, suggestion);
		}

		// 처음 보는 이름 — 1회 즉답을 칩 후보로만. 저장은 스케줄러가 3회 물어 확정한다
		String suggestion = menuKindClassifier.suggest(name).map(Enum::name).orElse(null);
		return new MenuRegistrationResult(menu.getId(), true, suggestion);
	}

	/** 회원이 칩을 탭해 kind 를 확정 — 본인이 등록한 메뉴만. 사람이 정한 값이라 캐시엔 넣지 않는다 */
	public void confirmKind(Long menuId, Long memberId, String kindName) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));
		if (menu.getCreatedByMember() == null || !menu.getCreatedByMember().getId().equals(memberId)) {
			throw new MenuException(MenuErrorCode.MENU_NOT_OWNER);
		}
		MenuKind kind;
		try {
			kind = MenuKind.valueOf(kindName);
		} catch (IllegalArgumentException notAKind) {
			throw new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND);
		}
		MenuCategory category = menuCategoryRepository.findByName(kind)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND));
		menu.applyClassification(category, true);
	}

	public void likeMenu(Long menuId, Long memberId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if (menuLikeRepository.existsByMemberIdAndMenuId(memberId, menuId)) {
			throw new MenuException(MenuErrorCode.ALREADY_LIKED);
		}

		menuLikeRepository.save(MenuLike.builder()
			.member(memberRepository.getReferenceById(memberId))
			.menu(menu)
			.build());
		menuRepository.incrementLikeCount(menuId);
	}

	public void cancelMenuLike(Long menuId, Long memberId) {
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		MenuLike menuLike = menuLikeRepository.findByMemberIdAndMenuId(memberId, menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.NOT_LIKED));

		menuLikeRepository.delete(menuLike);
		menuRepository.decrementLikeCount(menuId);
	}
}
