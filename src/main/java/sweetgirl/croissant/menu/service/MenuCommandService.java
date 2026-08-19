package sweetgirl.croissant.menu.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.member.service.MemberRestrictionReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.request.MenuCreateRequest;
import sweetgirl.croissant.menu.dto.response.MenuRegistrationResult;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuLike;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
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

	public MenuRegistrationResult registerMenu(Long storeId, Long memberId, MenuCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		memberRestrictionReader.validateCanWrite(memberId);
		Store store = storeRepository.findByIdForUpdate(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		String name = request.name().strip();
		String normalizedName = MenuNameNormalizer.normalize(name);
		return menuRepository.findByStoreIdAndNormalizedNameAndDeletedAtIsNull(storeId, normalizedName)
			.map(existing -> new MenuRegistrationResult(existing.getId(), false))
			.orElseGet(() -> createMemberMenu(store, member, name));
	}

	private MenuRegistrationResult createMemberMenu(Store store, Member member, String name) {
		Menu menu = menuRepository.save(Menu.builder()
			.store(store)
			.name(name)
			.createdByMember(member)
			.build());
		return new MenuRegistrationResult(menu.getId(), true);
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
