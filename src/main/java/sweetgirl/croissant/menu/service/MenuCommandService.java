package sweetgirl.croissant.menu.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuLike;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuLikeRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class MenuCommandService {

	private final MenuRepository menuRepository;
	private final MenuLikeRepository menuLikeRepository;
	private final MemberRepository memberRepository;

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
