package sweetgirl.croissant.alarm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NotificationSetting;
import sweetgirl.croissant.member.repository.NotificationSettingRepository;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class NotificationSettingProviderTest {

	@Mock
	NotificationSettingRepository notificationSettingRepository;

	@Mock
	MemberReader memberReader;

	@InjectMocks
	NotificationSettingProvider notificationSettingProvider;

	@Test
	@DisplayName("getOrCreate: 알림 설정이 이미 있으면 조회한 설정을 그대로 반환하고 저장하지 않는다")
	void getOrCreate_returnsExisting_whenPresent() {
		Long memberId = 1L;
		NotificationSetting existing = mock(NotificationSetting.class);
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.of(existing));

		NotificationSetting result = notificationSettingProvider.getOrCreate(memberId);

		assertThat(result).isSameAs(existing);
		verify(notificationSettingRepository, never()).save(any());
		verify(memberReader, never()).getMember(any());
	}

	@Test
	@DisplayName("getOrCreate: 알림 설정이 없으면 기본값으로 생성해 저장하고 반환한다")
	void getOrCreate_createsDefault_whenAbsent() {
		Long memberId = 1L;
		Member member = mock(Member.class);
		given(notificationSettingRepository.findByMemberId(memberId)).willReturn(Optional.empty());
		given(memberReader.getMember(memberId)).willReturn(member);
		given(notificationSettingRepository.save(any(NotificationSetting.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		NotificationSetting result = notificationSettingProvider.getOrCreate(memberId);

		assertThat(result).isNotNull();
		assertThat(result.getMember()).isSameAs(member);
		assertThat(result.isNotificationEnabled()).isTrue();
		assertThat(result.isMarketingEnabled()).isFalse();
		verify(notificationSettingRepository).save(any(NotificationSetting.class));
	}

	@Test
	@DisplayName("getOrCreate: memberId가 null이면 CustomException을 던진다")
	void getOrCreate_throws_whenMemberIdNull() {
		assertThatThrownBy(() -> notificationSettingProvider.getOrCreate(null))
			.isInstanceOf(CustomException.class);

		verify(notificationSettingRepository, never()).findByMemberId(any());
	}
}
