package sweetgirl.croissant.alarm.service;

import java.time.LocalTime;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.code.GeneralErrorCode;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.enums.NotificationRadius;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.service.MemberReader;

// 알림 설정 조회 헬퍼 — 없으면 기본값으로 생성. Query/Command 서비스가 공유한다.
@Component
@RequiredArgsConstructor
public class NotificationSettingProvider {

	private final NotificationSettingRepository notificationSettingRepository;
	private final MemberReader memberReader;

	public NotificationSetting getOrCreate(Long memberId) {
		if (memberId == null) {
			throw new CustomException(GeneralErrorCode._UNAUTHORIZED);
		}
		return notificationSettingRepository.findByMemberId(memberId)
			.orElseGet(() -> {
				Member member = memberReader.getMember(memberId);
				return notificationSettingRepository.save(
					NotificationSetting.builder()
						.member(member)
						.startTime(LocalTime.of(8, 0))
						.endTime(LocalTime.of(21, 0))
						.daysOfWeek("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY,SATURDAY,SUNDAY")
						.notificationRadius(NotificationRadius.R500)
						.isMarketingEnabled(false)
						.isNotificationEnabled(true)
						.build());
			});
	}
}
