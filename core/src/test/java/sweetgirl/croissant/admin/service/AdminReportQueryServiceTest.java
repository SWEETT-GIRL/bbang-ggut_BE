package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.admin.dto.response.AdminAbuseReportStatsResponse;
import sweetgirl.croissant.admin.dto.response.AdminHighPriorityReportResponse;
import sweetgirl.croissant.admin.dto.response.AdminReportResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;

@ExtendWith(MockitoExtension.class)
class AdminReportQueryServiceTest {

	@Mock
	ContributionReportRepository contributionReportRepository;

	@Mock
	StoreContributionReportRepository storeContributionReportRepository;

	@Mock
	MenuContributionReportRepository menuContributionReportRepository;

	@Mock
	AbuseReportRepository abuseReportRepository;

	@Mock
	StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;

	@Mock
	MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;

	@Mock
	ReviewRepository reviewRepository;

	@Mock
	ReviewImageFileRepository reviewImageFileRepository;

	@Mock
	AbuseReportPriorityAnalyzer priorityAnalyzer;

	@InjectMocks
	AdminReportQueryService adminReportQueryService;

	@Test
	@DisplayName("getReports: STORE_REPORT 조회 시 제보 목록을 페이지로 반환한다")
	void getReports_storeReport_returnsPage() {
		given(contributionReportRepository.findByTargetTypeAndReportType(
			eq(ContributionTargetType.STORE), eq(ContributionReportType.UPDATE), any()))
			.willReturn(Page.<ContributionReport>empty());
		given(storeContributionReportRepository.findByContributionReportIdIn(anyList())).willReturn(List.of());
		given(storeContributionReportImageFileRepository.findByStoreContributionReportIdIn(anyList()))
			.willReturn(List.of());

		Page<AdminReportResponse> result = adminReportQueryService.getReports("STORE_REPORT", null, 0, 20);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("getReports: 알 수 없는 종류이면 REPORT_INVALID_KIND 예외를 던진다")
	void getReports_throws_whenInvalidKind() {
		assertThatThrownBy(() -> adminReportQueryService.getReports("UNKNOWN", null, 0, 20))
			.isInstanceOf(AdminException.class);
	}

	@Test
	@DisplayName("getReportDetail: 존재하지 않는 제보이면 REPORT_NOT_FOUND 예외를 던진다")
	void getReportDetail_throws_whenNotFound() {
		given(storeContributionReportRepository.findByContributionReportId(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> adminReportQueryService.getReportDetail(99L, "STORE_ADD_REPORT"))
			.isInstanceOf(AdminException.class);
	}

	@Test
	@DisplayName("getReportDetail: 알 수 없는 종류이면 REPORT_INVALID_KIND 예외를 던진다")
	void getReportDetail_throws_whenInvalidKind() {
		assertThatThrownBy(() -> adminReportQueryService.getReportDetail(1L, "UNKNOWN"))
			.isInstanceOf(AdminException.class);
	}

	@Test
	@DisplayName("getAbuseReportStats: 우선순위 분석기에 통계 조회를 위임한다")
	void getAbuseReportStats_delegatesToAnalyzer() {
		AdminAbuseReportStatsResponse stats = mock(AdminAbuseReportStatsResponse.class);
		given(priorityAnalyzer.getAbuseReportStats()).willReturn(stats);

		AdminAbuseReportStatsResponse result = adminReportQueryService.getAbuseReportStats();

		assertThat(result).isSameAs(stats);
	}

	@Test
	@DisplayName("getHighPriorityReports: 상태 문자열을 파싱해 우선순위 분석기에 위임한다")
	void getHighPriorityReports_delegatesToAnalyzer() {
		Page<AdminHighPriorityReportResponse> page = Page.empty();
		given(priorityAnalyzer.getHighPriorityReports(AbuseReportStatus.PENDING, 0, 20)).willReturn(page);

		Page<AdminHighPriorityReportResponse> result = adminReportQueryService.getHighPriorityReports("PENDING", 0, 20);

		assertThat(result).isSameAs(page);
	}
}
