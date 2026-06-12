package sweetgirl.croissant.menu.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.ImageSaveRequest;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.dto.request.MenuAddReportRequest;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class MenuAddReportServiceTest {

	@Mock
	StoreRepository storeRepository;
	@Mock
	MenuCategoryRepository menuCategoryRepository;
	@Mock
	MemberRepository memberRepository;
	@Mock
	S3Service s3Service;
	@Mock
	ContributionReportRepository contributionReportRepository;
	@Mock
	MenuContributionReportRepository menuContributionReportRepository;
	@Mock
	MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	@InjectMocks
	MenuAddReportService menuAddReportService;

	@Test
	@DisplayName("createMenuAddReport: 메뉴 추가 제보와 ContributionReport 상세를 함께 저장한다")
	void createMenuAddReport_savesMenuAndContributionReports() {
		Store store = mock(Store.class);
		MenuCategory menuCategory = mock(MenuCategory.class);
		Member member = mock(Member.class);
		ContributionReport contributionReport = mock(ContributionReport.class);
		MenuAddReportRequest request = request();

		given(storeRepository.findById(1L)).willReturn(Optional.of(store));
		given(menuCategoryRepository.findById(2L)).willReturn(Optional.of(menuCategory));
		given(memberRepository.getReferenceById(3L)).willReturn(member);
		given(contributionReportRepository.save(any(ContributionReport.class))).willReturn(contributionReport);
		given(menuContributionReportRepository.save(any(MenuContributionReport.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		menuAddReportService.createMenuAddReport(1L, 3L, request);

		ArgumentCaptor<MenuContributionReport> reportCaptor = ArgumentCaptor.forClass(MenuContributionReport.class);
		verify(menuContributionReportRepository).save(reportCaptor.capture());
		verify(menuContributionReportImageFileRepository).saveAll(argThat(images -> images.iterator().hasNext()));
	}

	@Test
	@DisplayName("createMenuAddReport: store가 없으면 StoreException을 던진다")
	void createMenuAddReport_storeNotFound_throwsException() {
		given(storeRepository.findById(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuAddReportService.createMenuAddReport(1L, 3L, request()))
			.isInstanceOf(StoreException.class);
	}

	@Test
	@DisplayName("createMenuAddReport: 메뉴 카테고리가 없으면 MenuException을 던진다")
	void createMenuAddReport_menuCategoryNotFound_throwsException() {
		given(storeRepository.findById(1L)).willReturn(Optional.of(mock(Store.class)));
		given(menuCategoryRepository.findById(2L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> menuAddReportService.createMenuAddReport(1L, 3L, request()))
			.isInstanceOf(MenuException.class);
	}

	private MenuAddReportRequest request() {
		MenuAddReportRequest request = new MenuAddReportRequest();
		ReflectionTestUtils.setField(request, "menuCategoryId", 2L);
		ReflectionTestUtils.setField(request, "name", "소금빵");
		ReflectionTestUtils.setField(request, "price", 3500);
		ReflectionTestUtils.setField(request, "description", "겉바속촉");
		ReflectionTestUtils.setField(request, "images", List.of(image()));
		return request;
	}

	private ImageSaveRequest image() {
		ImageSaveRequest image = new ImageSaveRequest();
		ReflectionTestUtils.setField(image, "fileName", "salt.jpg");
		ReflectionTestUtils.setField(image, "awsKey", "temp/menu-add-reports/salt.jpg");
		ReflectionTestUtils.setField(image, "mimeType", "image/jpeg");
		return image;
	}
}
