package sweetgirl.croissant.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.inquiry.dto.request.InquiryCreateRequest;
import sweetgirl.croissant.inquiry.dto.response.InquiryCreateResponse;
import sweetgirl.croissant.inquiry.entity.Inquiry;
import sweetgirl.croissant.inquiry.enums.InquiryCategory;
import sweetgirl.croissant.inquiry.repository.InquiryRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;

@ExtendWith(MockitoExtension.class)
class InquiryServiceTest {

	@Mock
	InquiryRepository inquiryRepository;
	@Mock
	MemberReader memberReader;
	@Mock
	AdminReportNotificationPublisher adminReportNotificationPublisher;
	@InjectMocks
	InquiryService inquiryService;

	@Test
	void createInquiry_savesInquiryAndPublishesNotification() {
		Member member = mock(Member.class);
		InquiryCreateRequest request = new InquiryCreateRequest(InquiryCategory.BUG, "오류", "앱이 종료됩니다.");
		given(memberReader.getMember(1L)).willReturn(member);
		given(inquiryRepository.save(any(Inquiry.class))).willAnswer(invocation -> {
			Inquiry inquiry = invocation.getArgument(0);
			ReflectionTestUtils.setField(inquiry, "id", 10L);
			ReflectionTestUtils.setField(inquiry, "createdAt", LocalDateTime.of(2026, 6, 15, 12, 0));
			return inquiry;
		});

		InquiryCreateResponse response = inquiryService.createInquiry(1L, request);

		assertThat(response.inquiryId()).isEqualTo(10L);
		ArgumentCaptor<Inquiry> captor = ArgumentCaptor.forClass(Inquiry.class);
		verify(inquiryRepository).save(captor.capture());
		verify(adminReportNotificationPublisher).publishInquiryCreated(captor.getValue());
	}
}
