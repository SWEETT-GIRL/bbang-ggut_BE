package sweetgirl.croissant.member.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.response.ConsentResponse;
import sweetgirl.croissant.member.dto.response.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.member.dto.response.NotificationRadiusResponse;
import sweetgirl.croissant.member.dto.response.NotificationTimeResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.store.repository.SubscribedStoreRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberQueryService {

	private final SubscribedStoreRepository subscribedStoreRepository;
	private final LikedMenuCategoryRepository likedMenuCategoryRepository;
	private final NotificationSettingRepository notificationSettingRepository;
	private final MemberReader memberReader;

	public MemberProfileResponse getProfile(Long memberId) {
		return MemberProfileResponse.from(memberReader.getMember(memberId));
	}

	public long getSubscribedStoreCount(Long memberId) {
		Member member = memberReader.getMember(memberId);
		return subscribedStoreRepository.countByMember(member);
	}

	public ConsentResponse getConsent(Long memberId) {
		return ConsentResponse.from(memberReader.getMember(memberId));
	}

	//유저가 좋아한다고 한 빵 종류 조회
	public MenuCategoryResponse getLikedMenu(Long memberId) {
		List<MenuCategory> likedMenuCategories = likedMenuCategoryRepository.findMenuCategoriesByMemberId(memberId);
		String[] breadKinds = likedMenuCategories.stream()
			.map(menuCategory -> menuCategory.getName().name())
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

	// 유저의 현재 알림 반경 조회
	public NotificationRadiusResponse getNotificationRadius(Long memberId) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		// DB 저장값 → enum 변환 후 응답 반환
		NotificationRadius current = setting.getNotificationRadius();
		return NotificationRadiusResponse.of(current);
	}

	// 유저의 현재 알림 시간대 조회
	public NotificationTimeResponse getNotificationTime(Long memberId) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		return NotificationTimeResponse.of(setting);
	}
}
