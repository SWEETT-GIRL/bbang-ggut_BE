package sweetgirl.croissant.member.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.constraints.Null;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MenuKind;
import sweetgirl.croissant.common.enums.NotificationDay;
import sweetgirl.croissant.member.code.DayErrorCode;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.MenuCategoryResponse;
import sweetgirl.croissant.member.entity.LikedMenuCategory;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.exception.DayException;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
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
	private final NotificationSettingRepository notificationSettingRepository;

	@Transactional(readOnly = true)
	public long getSubscribedStoreCount(Long memberId) {
		Member member = memberRepository.findById(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
		return subscribedStoreRepository.countByMember(member);
	}

	//유저가 좋아한다고 한 빵종류 업데이트
	public void updateLikeMenuCategory(Long memberId, String[] likedList) {
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
		List<MenuCategory> likedMenuCategories = likedMenuCategoryRepository.findMenuCategoriesByMemberId(memberId);
		String[] breadKinds = likedMenuCategories.stream()
			.map(MenuCategory::getName)
			.toArray(String[]::new);
		return new MenuCategoryResponse(breadKinds);
	}

	//유저 알람 가능 요일 조회
	public DayOfWeekResponse getNotificationDays(Long memberId) {
		NotificationSetting notificationSetting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		String result = notificationSetting.getDaysOfWeek();
		DayOfWeekResponse dayList = new DayOfWeekResponse();

		if (result == null || result.isEmpty()) {
			return new DayOfWeekResponse();
		}

		dayList = new DayOfWeekResponse(result.split(","));

		return dayList;

	}

	//유저 알람 가능 요일 수정 ( 가입한 모든 유저에 한해서 가입시에는 NotificationSetting 테이블에 한 행씩 디폴트 값으로 생성 )
	public void updateNotificationDays(Long memberId, String dayList) {
		String[] spliteDays = dayList.split(",");

		for (String day : spliteDays) {
			try {
				NotificationDay.valueOf(day);
			} catch (IllegalArgumentException e) {
				throw new DayException(DayErrorCode.DAY_NOT_FOUND);
			}
		}

		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		setting.setDaysOfWeek(dayList);
	}
}
