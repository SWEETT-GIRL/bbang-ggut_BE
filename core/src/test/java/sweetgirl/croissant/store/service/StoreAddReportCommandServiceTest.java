package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;
import sweetgirl.croissant.store.dto.request.StoreAddReportRequest;
import sweetgirl.croissant.store.dto.response.StoreAddReportResponse;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.exception.StoreReportException;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreAddReportCommandServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	StoreRepository storeRepository;
	@Mock
	ContributionReportRepository contributionReportRepository;
	@Mock
	StoreContributionReportRepository storeContributionReportRepository;
	@Mock
	AdminReportNotificationPublisher adminReportNotificationPublisher;
	@InjectMocks
	StoreAddReportCommandService storeAddReportCommandService;

	@Test
	@DisplayName("createReport: 네이버 검색 결과 기반 가게 추가 제보를 저장한다")
	void createReport_savesStoreAddReport() {
		Member member = mock(Member.class);
		ContributionReport contributionReport = mock(ContributionReport.class);
		given(memberReader.getMember(1L)).willReturn(member);
		given(storeRepository.findByNaverPlaceId("123456")).willReturn(Optional.empty());
		given(storeContributionReportRepository.existsByNaverPlaceIdAndStatus("123456", StoreReportStatus.PENDING))
			.willReturn(false);
		given(storeContributionReportRepository.existsPendingByNameAndAddress(
			"새빵집", "서울 마포구 월드컵로 1", StoreReportStatus.PENDING))
			.willReturn(false);
		given(contributionReport.getId()).willReturn(10L);
		given(contributionReportRepository.save(org.mockito.ArgumentMatchers.any(ContributionReport.class)))
			.willReturn(contributionReport);
		given(storeContributionReportRepository.save(org.mockito.ArgumentMatchers.any(StoreContributionReport.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		StoreAddReportResponse response = storeAddReportCommandService.createReport(1L, request());

		assertThat(response.reportId()).isEqualTo(10L);
		assertThat(response.status()).isEqualTo(StoreReportStatus.PENDING);
		ArgumentCaptor<StoreContributionReport> captor = ArgumentCaptor.forClass(StoreContributionReport.class);
		verify(storeContributionReportRepository).save(captor.capture());
		assertThat(captor.getValue().getNaverPlaceId()).isEqualTo("123456");
		assertThat(captor.getValue().getName()).isEqualTo("새빵집");
		assertThat(captor.getValue().getAddress()).isEqualTo("서울 마포구 월드컵로 1");
		verify(adminReportNotificationPublisher).publishStoreContributionReportCreated(captor.getValue());
	}

	@Test
	@DisplayName("createReport: 같은 네이버 placeId의 pending 제보가 있으면 예외를 던진다")
	void createReport_duplicateNaverPlaceId_throws() {
		given(storeRepository.findByNaverPlaceId("123456")).willReturn(Optional.empty());
		given(storeContributionReportRepository.existsByNaverPlaceIdAndStatus("123456", StoreReportStatus.PENDING))
			.willReturn(true);

		assertThatThrownBy(() -> storeAddReportCommandService.createReport(null, request()))
			.isInstanceOf(StoreReportException.class);
	}

	private StoreAddReportRequest request() {
		return new StoreAddReportRequest(
			"123456",
			"새빵집",
			"서울 마포구 월드컵로 1",
			"서울 마포구 월드컵로 1",
			"02-123-4567",
			37.5665,
			126.978,
			"추가해주세요");
	}
}
