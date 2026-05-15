package sweetgirl.croissant.member.service;

import java.util.Arrays;
import java.util.List; // RefreshToken 목록을 받아오기 위해 import
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.alarm.repository.FcmTokenRepository; // 탈퇴 시 FCM 토큰 삭제를 위해 import
import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.dto.request.ConsentCreateRequest;
import sweetgirl.croissant.member.dto.request.ConsentUpdateRequest;
import sweetgirl.croissant.member.dto.request.NotificationRadiusRequest;
import sweetgirl.croissant.member.dto.request.NotificationTimeRequest;
import sweetgirl.croissant.member.dto.response.ConsentResponse;
import sweetgirl.croissant.member.dto.response.DayOfWeekResponse;
import sweetgirl.croissant.member.dto.response.MenuCategoryResponse;
import sweetgirl.croissant.member.dto.response.NotificationRadiusResponse;
import sweetgirl.croissant.member.dto.response.NotificationTimeResponse;
import sweetgirl.croissant.member.entity.LikedMenuCategory;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.entity.RefreshToken; // 탈퇴 시 revoke 처리할 토큰 엔티티
import sweetgirl.croissant.member.enums.NotificationDay;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.LikedMenuCategoryRepository;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.repository.RefreshTokenRepository; // 탈퇴 시 RefreshToken revoke를 위해 import
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;
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
	private final MemberReader memberReader;
	private final RefreshTokenRepository refreshTokenRepository; // 탈퇴 시 해당 회원의 모든 RefreshToken을 revoke하기 위해 주입
	private final FcmTokenRepository fcmTokenRepository; // 탈퇴 시 해당 회원의 모든 FCM 토큰을 삭제하기 위해 주입

	@Transactional(readOnly = true)
	public long getSubscribedStoreCount(Long memberId) {
		Member member = memberReader.getMember(memberId);
		return subscribedStoreRepository.countByMember(member);
	}

	public void createConsent(Long memberId, ConsentCreateRequest request) {
		Member member = memberReader.getMember(memberId);
		member.applyInitialConsent(
			request.termsAgreed(),
			request.privacyAgreed(),
			request.ageOver14Agreed(),
			request.marketingAgreed(),
			request.nightMarketingAgreed(),
			request.termsVersion(),
			request.privacyVersion());
	}

	@Transactional(readOnly = true)
	public ConsentResponse getConsent(Long memberId) {
		return ConsentResponse.from(memberReader.getMember(memberId));
	}

	public ConsentResponse updateConsent(Long memberId, ConsentUpdateRequest request) {
		Member member = memberReader.getMember(memberId);
		if (request.agreeToMarketing() != null) {
			member.updateMarketingAgreement(request.agreeToMarketing());
		}
		if (request.agreeToNightMarketing() != null) {
			member.updateNightMarketingAgreement(request.agreeToNightMarketing());
		}
		return ConsentResponse.from(member);
	}

	//유저가 좋아한다고 한 빵종류 업데이트
	@Transactional
	public void updateLikeMenuCategory(Long memberId, String[] likedList) {
		Member member = memberReader.getMember(memberId);

		likedMenuCategoryRepository.deleteByMemberId(memberId);

		for (String like : likedList) {
			MenuKind menuKind = MenuKind.valueOf(like);
			MenuCategory menuCategory = menuCategoryRepository.findByName(menuKind)
				.orElseThrow(() -> new MemberException(MemberErrorCode.MENU_NOT_FOUND));

			likedMenuCategoryRepository
				.save(LikedMenuCategory.builder().member(member).menuCategory(menuCategory).build());
		}
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

	//유저 알람 가능 요일 수정 ( 가입한 모든 유저에 한해서 가입시에는 NotificationSetting 테이블에 한 행씩 디폴트 값으로 생성 )
	public void updateNotificationDays(Long memberId, String dayList) {
		// StringHttpMessageConverter가 JSON 따옴표를 그대로 읽는 경우 제거 (예: `""` → ``)
		if (dayList != null) {
			dayList = dayList.trim();
			if (dayList.startsWith("\"") && dayList.endsWith("\"")) {
				dayList = dayList.substring(1, dayList.length() - 1);
			}
		}
		if (dayList == null || dayList.isBlank()) {
			NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
				.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));
			setting.setDaysOfWeekList("");
			return;
		}

		String[] splitDays = Arrays.stream(dayList.split(","))
			.map(String::trim)
			.filter(s -> !s.isBlank())
			.toArray(String[]::new);

		for (String day : splitDays) {
			NotificationDay.from(day);
		}

		String cleanedDayList = Arrays.stream(splitDays).collect(Collectors.joining(","));

		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		setting.setDaysOfWeekList(cleanedDayList);
	}

	// 유저의 현재 알림 반경 조회
	@Transactional(readOnly = true)
	public NotificationRadiusResponse getNotificationRadius(Long memberId) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		// DB 저장값 → enum 변환 후 응답 반환
		NotificationRadius current = setting.getNotificationRadius();
		return NotificationRadiusResponse.of(current);
	}

	// 유저의 알림 반경을 한 단계 UP 또는 DOWN으로 변경 후 변경된 반경 반환
	public NotificationRadiusResponse updateNotificationRadius(Long memberId, NotificationRadiusRequest request) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		// 현재 반경을 enum으로 변환
		NotificationRadius current = setting.getNotificationRadius();

		// direction에 따라 다음 단계 계산
		NotificationRadius next = request.isUp() ? current.next() : current.prev();

		// 이미 최대/최솟값이면 예외
		if (next == null) {
			throw new MemberException(
				request.isUp()
					? MemberErrorCode.NOTIFICATION_RADIUS_ALREADY_MAX
					: MemberErrorCode.NOTIFICATION_RADIUS_ALREADY_MIN);
		}

		// 변경된 반경 저장 (dirty checking으로 자동 반영)
		setting.updateRadius(next);
		return NotificationRadiusResponse.of(next);
	}

	// 유저의 현재 알림 시간대 조회
	@Transactional(readOnly = true)
	public NotificationTimeResponse getNotificationTime(Long memberId) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		return NotificationTimeResponse.of(setting);
	}

	// 유저의 알림 시간대 수정 후 변경된 시간대 반환
	public NotificationTimeResponse updateNotificationTime(Long memberId, NotificationTimeRequest request) {
		NotificationSetting setting = notificationSettingRepository.findByMemberId(memberId)
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

		// 시간대 업데이트 (dirty checking으로 자동 반영)
		setting.updateTime(request.toStartTime(), request.toEndTime());
		return NotificationTimeResponse.of(setting);
	}

	// 회원 탈퇴 (soft delete) — deletedAt 설정 후 토큰 무효화
	public void deleteMember(Long memberId) {
		if (memberId == null) {
			throw new MemberException(MemberErrorCode.UNAUTHORIZED);
		}
		Member member = memberRepository.findById(memberId) // id로 회원 조회
			.orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND)); // 존재하지 않으면 예외

		if (member.getDeletedAt() != null) { // 이미 탈퇴한 회원이면
			throw new MemberException(MemberErrorCode.MEMBER_ALREADY_DELETED); // 중복 탈퇴 방지
		}

		member.softDelete(); // deletedAt = now() 설정 (dirty checking으로 자동 반영)

		List<RefreshToken> refreshTokens = refreshTokenRepository.findAllByMember(member); // 해당 회원의 모든 RefreshToken 조회
		refreshTokens.forEach(RefreshToken::revoke); // 각 토큰의 revokedAt = now() 설정 → 재로그인 차단

		fcmTokenRepository.deleteAllByMember(member); // 해당 회원의 FCM 토큰 전체 삭제 → 탈퇴 후 푸시 알림 차단
	}
}
