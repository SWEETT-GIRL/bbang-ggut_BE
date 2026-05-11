package sweetgirl.croissant.member.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MenuKind;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.MenuCategoryResponse;
import sweetgirl.croissant.member.entity.LikedMenuCategory;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository memberRepository;
	private final SubscribedStoreRepository subscribedStoreRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final LikedMenuCategoryRepository likedMenuCategoryRepository;

	@Transactional(readOnly = true)
	public long getSubscribedStoreCount(Long memberId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		return subscribedStoreRepository.countByMember(member);
	}

	//유저가 좋아한다고 한 빵종류 업데이트
	public void updateLikedMenu(Long memberId, String[] likedList) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		for (String like : likedList) {
			MenuKind menuKind = MenuKind.valueOf(like);
			MenuCategory memuCatergory = menuCategoryRepository.findByName(menuKind)
				.orElseThrow(() -> new MemberException(MemberErrorCode.MENU_NOT_FOUND));

			likedMenuCategoryRepository
				.save(LikedMenuCategory.builder().member(member).menuCategory(memuCatergory).build());
		}
	}

	//유저가 좋아한다고 한 빵 종류 조회
	public MenuCategoryResponse getLikedMenu(Long memberId) {
		List<MenuCategory> list = likedMenuCategoryRepository.findMenuCategoriesByMemberId(memberId);
		String[] breadKinds = list.stream()
			.map(MenuCategory::getName)
			.toArray(String[]::new);
		return new MenuCategoryResponse(breadKinds);
	}

}
