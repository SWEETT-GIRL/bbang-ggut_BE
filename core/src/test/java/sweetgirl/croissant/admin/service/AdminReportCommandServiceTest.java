package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Point;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.abuse.entity.AbuseReport;
import sweetgirl.croissant.abuse.enums.AbuseReportStatus;
import sweetgirl.croissant.abuse.repository.AbuseReportRepository;
import sweetgirl.croissant.abuse.repository.ModerationActionRepository;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.common.enums.ReportKind;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReportImageFile;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReportImageFile;
import sweetgirl.croissant.contribution.enums.ContributionReportStatus;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.event.StoreSearchNameChangedEvent;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class AdminReportCommandServiceTest {

	@Mock
	StoreRepository storeRepository;
	@Mock
	StoreContributionReportRepository storeContributionReportRepository;
	@Mock
	MenuContributionReportRepository menuContributionReportRepository;
	@Mock
	AbuseReportRepository abuseReportRepository;
	@Mock
	ModerationActionRepository moderationActionRepository;
	@Mock
	StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	@Mock
	MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	StoreBusinessHourRepository storeBusinessHourRepository;
	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	ReviewRepository reviewRepository;
	@Mock
	S3Service s3Service;
	@Mock
	ImageProcessingCallbackService imageProcessingCallbackService;
	@Mock
	ReportNotificationService reportNotificationService;
	@Mock
	ApplicationEventPublisher eventPublisher;

	AdminReportCommandService adminReportCommandService;

	@BeforeEach
	void setUp() {
		ContributionReportProcessor contributionReportProcessor = new ContributionReportProcessor(
			storeRepository,
			storeContributionReportImageFileRepository,
			storeImageFileRepository,
			storeBusinessHourRepository,
			menuRepository,
			menuImageFileRepository,
			menuContributionReportImageFileRepository,
			s3Service,
			new ObjectMapper(),
			imageProcessingCallbackService,
			eventPublisher);
		adminReportCommandService = new AdminReportCommandService(
			storeContributionReportRepository,
			menuContributionReportRepository,
			abuseReportRepository,
			moderationActionRepository,
			reviewRepository,
			reportNotificationService,
			contributionReportProcessor);
	}

	@Test
	@DisplayName("StoreContribution ADD ACCEPT: Store 생성 + 승인 알림 발송")
	void storeAddReport_accept_createsStoreAndSendsNotification() {
		Member reporter = member(1L);
		StoreContributionReport report = storeAddContribution(
			13L, reporter, "123456", "새빵집", "서울 마포구 월드컵로 1", mock(Point.class));
		given(storeContributionReportRepository.findByContributionReportId(13L)).willReturn(Optional.of(report));
		given(storeRepository.findByNaverPlaceId("123456")).willReturn(Optional.empty());
		given(storeRepository.findActiveStoresByCompactName("새빵집")).willReturn(List.of());

		Store savedStore = store("새빵집", 100L);
		given(storeRepository.save(any(Store.class))).willReturn(savedStore);

		adminReportCommandService.reviewReport(13L, new AdminReportReviewRequest("STORE_ADD_REPORT", "ACCEPT"));

		verify(storeRepository).save(any(Store.class));
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.ACCEPTED);
		assertThat(report.getContributionReport().getStatus()).isEqualTo(ContributionReportStatus.ACCEPTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(savedStore), eq("새빵집"), eq(13L), eq(13L), eq(ReportKind.STORE_ADD_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
		// 새 가게가 자동완성에 바로 나오도록 그 이름으로 걸리는 캐시를 지운다
		verify(eventPublisher).publishEvent(StoreSearchNameChangedEvent.of("새빵집"));
	}

	@Test
	@DisplayName("StoreContribution ADD REJECT: Store 생성 없이 거절 알림 발송")
	void storeAddReport_reject_skipsStoreCreationAndSendsNotification() {
		Member reporter = member(1L);
		StoreContributionReport report = storeAddContribution(
			14L, reporter, "123456", "새빵집", "서울 마포구 월드컵로 1", mock(Point.class));
		given(storeContributionReportRepository.findByContributionReportId(14L)).willReturn(Optional.of(report));

		adminReportCommandService.reviewReport(14L, new AdminReportReviewRequest("STORE_ADD_REPORT", "REJECT"));

		verify(storeRepository, never()).save(any(Store.class));
		verify(eventPublisher, never()).publishEvent(any(StoreSearchNameChangedEvent.class));
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.REJECTED);
		assertThat(report.getContributionReport().getStatus()).isEqualTo(ContributionReportStatus.REJECTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(null), eq("새빵집"), eq(14L), eq(14L), eq(ReportKind.STORE_ADD_REPORT),
			eq(ReportNotificationAction.REJECTED), anyString());
	}

	@Test
	@DisplayName("MenuContribution ADD ACCEPT: Menu 신규 생성 + 이미지 S3 이동 + 알림 발송")
	void menuAddReport_accept_createsMenuAndMovesImages() {
		Store store = store("가게", 8L);
		Member reporter = member(2L);
		MenuContributionReport report = menuAddContribution(11L, reporter, store);
		given(menuContributionReportRepository.findByContributionReportId(11L)).willReturn(Optional.of(report));

		Menu newMenu = mock(Menu.class);
		given(newMenu.getId()).willReturn(99L);
		given(menuRepository.save(any(Menu.class))).willReturn(newMenu);

		MenuContributionReportImageFile img = MenuContributionReportImageFile.builder()
			.menuContributionReport(report)
			.name("file.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("menu-add-reports/temp/abc/uuid-file")
			.build();
		given(menuContributionReportImageFileRepository.findByMenuContributionReportIdIn(List.of(11L)))
			.willReturn(List.of(img));

		adminReportCommandService.reviewReport(11L, new AdminReportReviewRequest("MENU_ADD_REPORT", "ACCEPT"));

		verify(menuRepository).save(any(Menu.class));
		verify(s3Service).copyObject("menu-add-reports/temp/abc/uuid-file", "menus/99/uuid-file");
		verify(s3Service).deleteObject("menu-add-reports/temp/abc/uuid-file");
		verify(menuImageFileRepository).saveAll(anyList());
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.ACCEPTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(store), eq(null), eq(11L), eq(11L), eq(ReportKind.MENU_ADD_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("StoreContribution MOD_BAKERY_TIME ACCEPT: 기존 영업시간 삭제 후 제보 내용으로 재삽입")
	void storeReport_modBakeryTime_accept_replacesBusinessHours() {
		Store store = store("가게", 3L);
		Member reporter = member(3L);
		StoreContributionReport report = storeUpdateContribution(
			50L,
			reporter,
			store,
			StoreReportType.MOD_BAKERY_TIME,
			"{\"hours\":["
				+ "{\"dayOfWeek\":\"MONDAY\",\"closed\":false,\"openTime\":\"09:00\",\"closeTime\":\"21:00\"},"
				+ "{\"dayOfWeek\":\"SUNDAY\",\"closed\":true,\"openTime\":null,\"closeTime\":null}"
				+ "],\"reason\":\"확인\"}");
		given(storeContributionReportRepository.findByContributionReportId(50L)).willReturn(Optional.of(report));

		adminReportCommandService.reviewReport(50L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(storeBusinessHourRepository).deleteByStoreId(3L);
		ArgumentCaptor<List<StoreBusinessHour>> captor = ArgumentCaptor.forClass(List.class);
		verify(storeBusinessHourRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).hasSize(2);
		assertThat(captor.getValue().get(0).getOpenTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(captor.getValue().get(0).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
		assertThat(captor.getValue().get(1).isClosed()).isTrue();
		assertThat(report.getStatus()).isEqualTo(StoreReportStatus.ACCEPTED);
		verify(reportNotificationService).notifyContributionReportResult(
			eq(reporter), eq(store), eq(null), eq(50L), eq(50L), eq(ReportKind.STORE_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("StoreContribution MOD_BAKERY_ADDRESS ACCEPT: Store.update 호출")
	void storeReport_modBakeryAddress_accept_updatesStore() {
		Store store = mock(Store.class);
		Member reporter = member(4L);
		StoreContributionReport report = storeUpdateContribution(
			61L,
			reporter,
			store,
			StoreReportType.MOD_BAKERY_ADDRESS,
			"{\"address\":\"서울 마포구 와우산로 1\","
				+ "\"roadAddress\":\"서울 마포구 와우산로 1\","
				+ "\"placeName\":\"빵집\","
				+ "\"latitude\":37.55,\"longitude\":126.93,\"reason\":null}");
		given(storeContributionReportRepository.findByContributionReportId(61L)).willReturn(Optional.of(report));

		adminReportCommandService.reviewReport(61L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(store).update(
			eq(null),
			eq("서울 마포구 와우산로 1"),
			any(),
			eq("서울"),
			eq("마포구"),
			// 도로명은 동 자리에 넣지 않는다 (AddressParser, 2026-09-10)
			eq((String)null),
			eq(null),
			eq(null),
			eq(null));
	}

	@Test
	@DisplayName("StoreContribution MOD_BAKERY_IMG ACCEPT: delete + add 이미지 처리")
	void storeReport_modBakeryImg_accept_addAndDeleteImages() {
		Store store = store("가게", 5L);
		Member reporter = member(5L);
		StoreContributionReport report = storeUpdateContribution(
			70L,
			reporter,
			store,
			StoreReportType.MOD_BAKERY_IMG,
			"{\"deleteImageIds\":[100,101],"
				+ "\"addImages\":[{\"fileName\":\"a.jpg\",\"contentType\":\"image/jpeg\","
				+ "\"awsKey\":\"reports/stores/5/members/9/images/key-a\"}],"
				+ "\"reason\":null}");
		given(storeContributionReportRepository.findByContributionReportId(70L)).willReturn(Optional.of(report));

		StoreImageFile existing1 = mock(StoreImageFile.class);
		given(existing1.getAwsKey()).willReturn("stores/5/old1");
		StoreImageFile existing2 = mock(StoreImageFile.class);
		given(existing2.getAwsKey()).willReturn("stores/5/old2");
		given(storeImageFileRepository.findByIdInAndStoreId(List.of(100L, 101L), 5L))
			.willReturn(List.of(existing1, existing2));
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(5L)).willReturn(List.of());

		StoreContributionReportImageFile src = StoreContributionReportImageFile.builder()
			.storeContributionReport(report)
			.name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("reports/stores/5/members/9/images/key-a")
			.build();
		given(storeContributionReportImageFileRepository.findByStoreContributionReportIdIn(List.of(70L)))
			.willReturn(List.of(src));

		adminReportCommandService.reviewReport(70L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(s3Service).deleteObject("stores/5/old1");
		verify(s3Service).deleteObject("stores/5/old2");
		verify(storeImageFileRepository).deleteAll(List.of(existing1, existing2));
		verify(s3Service).copyObject("reports/stores/5/members/9/images/key-a", "stores/5/key-a");
		verify(s3Service).deleteObject("reports/stores/5/members/9/images/key-a");
		verify(storeImageFileRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("MenuContribution MOD_BREAD ACCEPT: menu.price 갱신 + 이미지 처리")
	void menuReport_modBread_accept_updatesMenuAndImages() {
		Store store = store("가게", 6L);
		Member reporter = member(6L);
		Menu menu = mock(Menu.class);
		given(menu.getId()).willReturn(200L);
		MenuContributionReport report = menuUpdateContribution(
			80L,
			reporter,
			store,
			menu,
			"{\"price\":4500,\"deleteImageIds\":[301],"
				+ "\"addImages\":[{\"fileName\":\"m.jpg\",\"contentType\":\"image/jpeg\","
				+ "\"awsKey\":\"reports/stores/6/menus/200/members/9/key-m\"}],"
				+ "\"reason\":null}");
		given(menuContributionReportRepository.findByContributionReportId(80L)).willReturn(Optional.of(report));

		MenuImageFile existing = mock(MenuImageFile.class);
		given(existing.getAwsKey()).willReturn("menus/200/oldmenu");
		given(menuImageFileRepository.findByIdInAndMenuId(List.of(301L), 200L)).willReturn(List.of(existing));
		given(menuImageFileRepository.findImagesByMenuId(200L)).willReturn(List.of());

		MenuContributionReportImageFile src = MenuContributionReportImageFile.builder()
			.menuContributionReport(report)
			.name("m.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("reports/stores/6/menus/200/members/9/key-m")
			.build();
		given(menuContributionReportImageFileRepository.findByMenuContributionReportIdIn(List.of(80L)))
			.willReturn(List.of(src));

		adminReportCommandService.reviewReport(80L, new AdminReportReviewRequest("MENU_REPORT", "ACCEPT"));

		verify(menu).update(null, 4500, null, null);
		verify(s3Service).deleteObject("menus/200/oldmenu");
		verify(s3Service).copyObject("reports/stores/6/menus/200/members/9/key-m", "menus/200/key-m");
		verify(s3Service).deleteObject("reports/stores/6/menus/200/members/9/key-m");
	}

	@Test
	@DisplayName("AbuseReport ACCEPT: 신고 상태를 승인 처리하고 신고자에게 결과 알림을 발송한다")
	void abuseReport_accept_updatesStatusAndNotifiesReporter() {
		AbuseReport report = mock(AbuseReport.class);
		given(report.getStatus()).willReturn(AbuseReportStatus.PENDING);
		given(abuseReportRepository.findByIdWithMembers(31L)).willReturn(Optional.of(report));

		adminReportCommandService.reviewReport(31L, new AdminReportReviewRequest("ABUSE_REPORT", "ACCEPT"));

		verify(report).accept();
		verify(report, never()).reject();
		verify(reportNotificationService).notifyAbuseReportResult(report);
	}

	@Test
	@DisplayName("이미 처리된 contribution 제보는 다시 리뷰할 수 없다")
	void reviewReport_alreadyReviewed_throws() {
		StoreContributionReport report = storeUpdateContribution(
			99L, member(9L), store("가게", 9L), StoreReportType.MOD_BAKERY_IMG, "{}");
		report.approve();
		given(storeContributionReportRepository.findByContributionReportId(99L)).willReturn(Optional.of(report));

		assertThatThrownBy(() -> adminReportCommandService.reviewReport(
			99L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT")))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_ALREADY_REVIEWED.getMessage());

		verify(reportNotificationService, never()).notifyContributionReportResult(
			any(), any(), any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("kind가 잘못되면 예외를 던진다")
	void reviewReport_invalidKind_throws() {
		assertThatThrownBy(() -> adminReportCommandService.reviewReport(
			1L, new AdminReportReviewRequest("UNKNOWN", "ACCEPT")))
			.isInstanceOf(AdminException.class);
	}

	private StoreContributionReport storeAddContribution(
			Long contributionReportId,
			Member member,
			String naverPlaceId,
			String name,
			String address,
			Point location) {
		StoreContributionReport report = StoreContributionReport.builder()
			.contributionReport(contributionReport(
				contributionReportId, member, ContributionTargetType.STORE, ContributionReportType.ADD))
			.requestType("STORE_ADD")
			.naverPlaceId(naverPlaceId)
			.name(name)
			.address(address)
			.roadAddress(address)
			.phone("02-123-4567")
			.location(location)
			.reason("추가해주세요")
			.build();
		ReflectionTestUtils.setField(report, "id", contributionReportId);
		return report;
	}

	private StoreContributionReport storeUpdateContribution(
			Long contributionReportId,
			Member member,
			Store store,
			StoreReportType requestType,
			String message) {
		StoreContributionReport report = StoreContributionReport.builder()
			.contributionReport(contributionReport(
				contributionReportId, member, ContributionTargetType.STORE, ContributionReportType.UPDATE))
			.store(store)
			.requestType(requestType.name())
			.message(message)
			.build();
		ReflectionTestUtils.setField(report, "id", contributionReportId);
		return report;
	}

	private MenuContributionReport menuAddContribution(Long contributionReportId, Member member, Store store) {
		MenuContributionReport report = MenuContributionReport.builder()
			.contributionReport(contributionReport(
				contributionReportId, member, ContributionTargetType.MENU, ContributionReportType.ADD))
			.store(store)
			.menuCategory(mock(MenuCategory.class))
			.requestType("MENU_ADD")
			.name("크림빵")
			.price(3500)
			.description("부드러운 크림빵")
			.build();
		ReflectionTestUtils.setField(report, "id", contributionReportId);
		return report;
	}

	private MenuContributionReport menuUpdateContribution(
			Long contributionReportId,
			Member member,
			Store store,
			Menu menu,
			String message) {
		MenuContributionReport report = MenuContributionReport.builder()
			.contributionReport(contributionReport(
				contributionReportId, member, ContributionTargetType.MENU, ContributionReportType.UPDATE))
			.store(store)
			.menu(menu)
			.requestType(StoreReportType.MOD_BREAD.name())
			.message(message)
			.build();
		ReflectionTestUtils.setField(report, "id", contributionReportId);
		return report;
	}

	private ContributionReport contributionReport(
			Long id,
			Member member,
			ContributionTargetType targetType,
			ContributionReportType reportType) {
		ContributionReport contributionReport = ContributionReport.builder()
			.member(member)
			.targetType(targetType)
			.reportType(reportType)
			.build();
		ReflectionTestUtils.setField(contributionReport, "id", id);
		return contributionReport;
	}

	private Member member(Long id) {
		Member member = Member.builder()
			.nickname("member-" + id)
			.build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Store store(String name, Long id) {
		Store store = Store.builder()
			.name(name)
			.address("서울 마포구 월드컵로 1")
			.region1st("서울")
			.region2nd("마포구")
			.region3rd("월드컵로")
			.location(mock(Point.class))
			.phone("02-123-4567")
			.source(StoreSource.ADMIN_REGISTERED)
			.category(StoreCategory.BAKERY)
			.build();
		ReflectionTestUtils.setField(store, "id", id);
		return store;
	}
}
