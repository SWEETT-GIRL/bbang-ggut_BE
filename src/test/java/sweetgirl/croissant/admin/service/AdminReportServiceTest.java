package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.dto.request.AdminModerationActionRequest;
import sweetgirl.croissant.admin.dto.request.AdminReportReviewRequest;
import sweetgirl.croissant.admin.dto.response.AdminModerationActionResponse;
import sweetgirl.croissant.admin.dto.response.AdminUserReportDetailResponse;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.alarm.enums.ReportKind;
import sweetgirl.croissant.alarm.enums.ReportNotificationAction;
import sweetgirl.croissant.alarm.service.ReportNotificationService;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.enums.MimeType;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuAddReportImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuAddReportRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.report.entity.ModerationAction;
import sweetgirl.croissant.report.entity.UserReport;
import sweetgirl.croissant.report.enums.ReportReasonType;
import sweetgirl.croissant.report.enums.ReportTargetType;
import sweetgirl.croissant.report.enums.UserReportAction;
import sweetgirl.croissant.report.enums.UserReportStatus;
import sweetgirl.croissant.report.repository.ModerationActionRepository;
import sweetgirl.croissant.report.repository.UserReportRepository;
import sweetgirl.croissant.review.entity.Review;
import sweetgirl.croissant.review.entity.ReviewImageFile;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewRepository;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreBusinessHour;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.entity.StoreReportImageFile;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportRepository;

@ExtendWith(MockitoExtension.class)
class AdminReportServiceTest {

	@Mock
	StoreReportRepository storeReportRepository;
	@Mock
	MenuAddReportRepository menuAddReportRepository;
	@Mock
	UserReportRepository userReportRepository;
	@Mock
	ModerationActionRepository moderationActionRepository;
	@Mock
	StoreReportImageFileRepository storeReportImageFileRepository;
	@Mock
	MenuAddReportImageFileRepository menuAddReportImageFileRepository;
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
	ReviewImageFileRepository reviewImageFileRepository;
	@Mock
	S3Service s3Service;
	@Mock
	ReportNotificationService reportNotificationService;

	AdminReportService adminReportService;

	@BeforeEach
	void setUp() {
		adminReportService = new AdminReportService(
			storeReportRepository,
			menuAddReportRepository,
			userReportRepository,
			moderationActionRepository,
			storeReportImageFileRepository,
			menuAddReportImageFileRepository,
			storeImageFileRepository,
			storeBusinessHourRepository,
			menuRepository,
			menuImageFileRepository,
			reviewRepository,
			reviewImageFileRepository,
			s3Service,
			new ObjectMapper(),
			reportNotificationService);
	}

	@Test
	@DisplayName("UserReport 상세 조회: 리뷰 대상 원문과 이미지를 반환한다")
	void getReportDetail_userReportReview_returnsReviewTarget() {
		Member reporter = mock(Member.class);
		given(reporter.getId()).willReturn(10L);
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		UserReport report = mock(UserReport.class);
		given(report.getId()).willReturn(1L);
		given(report.getTargetType()).willReturn(ReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(123L);
		given(report.getReasonType()).willReturn(ReportReasonType.ABUSE);
		given(report.getReasonDetail()).willReturn("욕설");
		given(report.getStatus()).willReturn(UserReportStatus.PENDING);
		given(report.getModerationAction()).willReturn(UserReportAction.NONE);
		given(report.getReporter()).willReturn(reporter);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(userReportRepository.findByIdWithMembers(1L)).willReturn(Optional.of(report));

		Store store = mock(Store.class);
		given(store.getId()).willReturn(2L);
		given(store.getName()).willReturn("빵집");
		Menu menu = mock(Menu.class);
		given(menu.getId()).willReturn(3L);
		given(menu.getName()).willReturn("소금빵");
		given(menu.getStore()).willReturn(store);
		Review review = mock(Review.class);
		given(review.getId()).willReturn(123L);
		given(review.getMenu()).willReturn(menu);
		given(review.getMember()).willReturn(reportedMember);
		given(review.getContent()).willReturn("문제된 리뷰");
		given(reviewRepository.findByIdWithDetails(123L)).willReturn(Optional.of(review));

		ReviewImageFile image = mock(ReviewImageFile.class);
		given(image.getAwsKey()).willReturn("reviews/members/20/images/a.jpg");
		given(reviewImageFileRepository.findByReviewIdOrderByDisplayOrderAscIdAsc(123L))
			.willReturn(List.of(image));

		AdminUserReportDetailResponse response = adminReportService.getReportDetail(1L, "USER_REPORT");

		assertThat(response.reportId()).isEqualTo(1L);
		assertThat(response.targetType()).isEqualTo("REVIEW");
		assertThat(response.reporterId()).isEqualTo(10L);
		assertThat(response.reportedMemberId()).isEqualTo(20L);
		assertThat(response.target()).isInstanceOf(AdminUserReportDetailResponse.ReviewTarget.class);
		var target = (AdminUserReportDetailResponse.ReviewTarget)response.target();
		assertThat(target.reviewId()).isEqualTo(123L);
		assertThat(target.menuName()).isEqualTo("소금빵");
		assertThat(target.storeName()).isEqualTo("빵집");
		assertThat(target.content()).isEqualTo("문제된 리뷰");
		assertThat(target.images()).containsExactly("reviews/members/20/images/a.jpg");
	}

	@Test
	@DisplayName("UserReport 상세 조회: 프로필 대상이면 신고된 회원 프로필 정보를 반환한다")
	void getReportDetail_userReportMemberProfile_returnsMemberTarget() {
		Member reporter = mock(Member.class);
		given(reporter.getId()).willReturn(10L);
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		given(reportedMember.getNickname()).willReturn("부적절닉네임");
		UserReport report = mock(UserReport.class);
		given(report.getId()).willReturn(2L);
		given(report.getTargetType()).willReturn(ReportTargetType.MEMBER_PROFILE);
		given(report.getTargetId()).willReturn(20L);
		given(report.getReasonType()).willReturn(ReportReasonType.ETC);
		given(report.getStatus()).willReturn(UserReportStatus.PENDING);
		given(report.getModerationAction()).willReturn(UserReportAction.NONE);
		given(report.getReporter()).willReturn(reporter);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(userReportRepository.findByIdWithMembers(2L)).willReturn(Optional.of(report));

		AdminUserReportDetailResponse response = adminReportService.getReportDetail(2L, "USER_REPORT");

		assertThat(response.targetType()).isEqualTo("MEMBER_PROFILE");
		assertThat(response.target()).isInstanceOf(AdminUserReportDetailResponse.MemberProfileTarget.class);
		var target = (AdminUserReportDetailResponse.MemberProfileTarget)response.target();
		assertThat(target.memberId()).isEqualTo(20L);
		assertThat(target.nickname()).isEqualTo("부적절닉네임");
	}

	@Test
	@DisplayName("UserReport 상세 조회: USER_REPORT 외 kind는 예외를 던진다")
	void getReportDetail_invalidKind_throws() {
		assertThatThrownBy(() -> adminReportService.getReportDetail(1L, "STORE_REPORT"))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_INVALID_KIND.getMessage());
	}

	@Test
	@DisplayName("ModerationAction HIDE_REVIEW: 승인된 리뷰 신고이면 리뷰를 숨기고 이력을 저장한다")
	void applyModerationAction_hideReview_hidesReviewAndRecordsAction() {
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		UserReport report = mock(UserReport.class);
		given(report.getId()).willReturn(31L);
		given(report.getStatus()).willReturn(UserReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(ReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(123L);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(userReportRepository.findByIdWithMembers(31L)).willReturn(Optional.of(report));

		Review review = mock(Review.class);
		given(reviewRepository.findByIdWithDetails(123L)).willReturn(Optional.of(review));
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		AdminModerationActionResponse response = adminReportService.applyModerationAction(
			31L,
			new AdminModerationActionRequest("HIDE_REVIEW", " 욕설 포함 ", true));

		assertThat(response.action()).isEqualTo("HIDE_REVIEW");
		assertThat(response.reason()).isEqualTo("욕설 포함");
		verify(review).hide("욕설 포함", null);
		verify(report).applyModerationAction(UserReportAction.HIDE_REVIEW);
		verify(moderationActionRepository).save(argThat(action -> action.getReport() == report
			&& action.getTargetType() == ReportTargetType.REVIEW
			&& action.getTargetId().equals(123L)
			&& action.getMember() == reportedMember
			&& action.getAction() == UserReportAction.HIDE_REVIEW
			&& action.getReason().equals("욕설 포함")));
	}

	@Test
	@DisplayName("ModerationAction DELETE_REVIEW: 리뷰 soft delete를 재사용한다")
	void applyModerationAction_deleteReview_softDeletesReview() {
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		UserReport report = mock(UserReport.class);
		given(report.getId()).willReturn(32L);
		given(report.getStatus()).willReturn(UserReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(ReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(124L);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(userReportRepository.findByIdWithMembers(32L)).willReturn(Optional.of(report));

		Review review = mock(Review.class);
		given(reviewRepository.findByIdWithDetails(124L)).willReturn(Optional.of(review));
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		adminReportService.applyModerationAction(
			32L,
			new AdminModerationActionRequest("DELETE_REVIEW", "반복 욕설", false));

		verify(review).softDelete();
		verify(review, never()).hide(anyString(), any());
		verify(report).applyModerationAction(UserReportAction.DELETE_REVIEW);
	}

	@Test
	@DisplayName("ModerationAction RESTORE_REVIEW: 직전 조치가 삭제이면 삭제와 숨김 상태를 복구한다")
	void applyModerationAction_restoreReview_restoresDeletedReview() {
		Member reportedMember = mock(Member.class);
		given(reportedMember.getId()).willReturn(20L);
		UserReport report = mock(UserReport.class);
		given(report.getId()).willReturn(33L);
		given(report.getStatus()).willReturn(UserReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(ReportTargetType.REVIEW);
		given(report.getTargetId()).willReturn(125L);
		given(report.getReportedMember()).willReturn(reportedMember);
		given(report.getModerationAction()).willReturn(UserReportAction.DELETE_REVIEW);
		given(userReportRepository.findByIdWithMembers(33L)).willReturn(Optional.of(report));

		Review review = mock(Review.class);
		given(reviewRepository.findByIdWithDetails(125L)).willReturn(Optional.of(review));
		given(moderationActionRepository.save(any(ModerationAction.class)))
			.willAnswer(invocation -> invocation.getArgument(0));

		adminReportService.applyModerationAction(
			33L,
			new AdminModerationActionRequest("RESTORE_REVIEW", "오조치 복구", false));

		verify(review).restoreModeration();
		verify(report).applyModerationAction(UserReportAction.RESTORE_REVIEW);
	}

	@Test
	@DisplayName("ModerationAction: 승인되지 않은 사용자 신고에는 조치할 수 없다")
	void applyModerationAction_pendingReport_throws() {
		UserReport report = mock(UserReport.class);
		given(report.getStatus()).willReturn(UserReportStatus.PENDING);
		given(userReportRepository.findByIdWithMembers(34L)).willReturn(Optional.of(report));

		assertThatThrownBy(() -> adminReportService.applyModerationAction(
			34L,
			new AdminModerationActionRequest("HIDE_REVIEW", "사유", false)))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_MODERATION_NOT_ALLOWED.getMessage());

		verify(moderationActionRepository, never()).save(any());
	}

	@Test
	@DisplayName("ModerationAction: 프로필 신고에는 리뷰 조치를 적용할 수 없다")
	void applyModerationAction_memberProfileReport_throws() {
		UserReport report = mock(UserReport.class);
		given(report.getStatus()).willReturn(UserReportStatus.ACCEPTED);
		given(report.getTargetType()).willReturn(ReportTargetType.MEMBER_PROFILE);
		given(userReportRepository.findByIdWithMembers(35L)).willReturn(Optional.of(report));

		assertThatThrownBy(() -> adminReportService.applyModerationAction(
			35L,
			new AdminModerationActionRequest("HIDE_REVIEW", "사유", false)))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_TARGET_TYPE_MISMATCH.getMessage());

		verify(moderationActionRepository, never()).save(any());
	}

	@Test
	@DisplayName("MenuAddReport ACCEPT: Menu 신규 생성 + 이미지 S3 이동 + MenuImageFile 저장 + 알림 발송")
	void menuAddReport_accept_createsMenuAndMovesImages() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		MenuCategory category = mock(MenuCategory.class);

		MenuAddReport report = mock(MenuAddReport.class);
		given(report.getId()).willReturn(11L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMenuCategory()).willReturn(category);
		given(report.getName()).willReturn("크림빵");
		given(report.getPrice()).willReturn(3500);
		given(report.getDescription()).willReturn("부드러운 크림빵");
		given(menuAddReportRepository.findById(11L)).willReturn(Optional.of(report));

		Menu newMenu = mock(Menu.class);
		given(newMenu.getId()).willReturn(99L);
		given(menuRepository.save(any(Menu.class))).willReturn(newMenu);

		MenuAddReportImageFile img = mock(MenuAddReportImageFile.class);
		given(img.getAwsKey()).willReturn("menu-add-reports/temp/abc/uuid-file");
		given(img.getName()).willReturn("file.jpg");
		given(img.getMimeType()).willReturn(MimeType.IMAGE_JPEG);
		given(menuAddReportImageFileRepository.findByMenuAddReportIdIn(List.of(11L)))
			.willReturn(List.of(img));

		adminReportService.reviewReport(11L, new AdminReportReviewRequest("MENU_ADD_REPORT", "ACCEPT"));

		verify(menuRepository).save(any(Menu.class));
		verify(s3Service).copyObject("menu-add-reports/temp/abc/uuid-file", "menus/99/uuid-file");
		verify(s3Service).deleteObject("menu-add-reports/temp/abc/uuid-file");
		verify(menuImageFileRepository).saveAll(anyList());
		verify(report).approve();
		verify(reportNotificationService).notifyReportResult(
			eq(reporter), eq(store), eq(11L), eq(ReportKind.MENU_ADD_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("MenuAddReport REJECT: Menu 생성 안 함 + 이미지만 S3 삭제 + 거절 알림")
	void menuAddReport_reject_skipsMenuCreation() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		MenuAddReport report = mock(MenuAddReport.class);
		given(report.getId()).willReturn(12L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(menuAddReportRepository.findById(12L)).willReturn(Optional.of(report));

		MenuAddReportImageFile img = mock(MenuAddReportImageFile.class);
		given(img.getAwsKey()).willReturn("menu-add-reports/temp/xyz/aaa");
		given(menuAddReportImageFileRepository.findByMenuAddReportIdIn(List.of(12L)))
			.willReturn(List.of(img));

		adminReportService.reviewReport(12L, new AdminReportReviewRequest("MENU_ADD_REPORT", "REJECT"));

		verify(menuRepository, never()).save(any(Menu.class));
		verify(s3Service).deleteObject("menu-add-reports/temp/xyz/aaa");
		verify(s3Service, never()).copyObject(anyString(), anyString());
		verify(report).reject();
		verify(reportNotificationService).notifyReportResult(
			eq(reporter), eq(store), eq(12L), eq(ReportKind.MENU_ADD_REPORT),
			eq(ReportNotificationAction.REJECTED), anyString());
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_TIME ACCEPT: 기존 영업시간 삭제 후 제보 내용으로 재삽입")
	void storeReport_modBakeryTime_accept_replacesBusinessHours() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(3L);
		Member reporter = mock(Member.class);
		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(50L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_TIME);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"hours\":["
				+ "{\"dayOfWeek\":\"MONDAY\",\"closed\":false,\"openTime\":\"09:00\",\"closeTime\":\"21:00\"},"
				+ "{\"dayOfWeek\":\"SUNDAY\",\"closed\":true,\"openTime\":null,\"closeTime\":null}"
				+ "],\"reason\":\"확인\"}");
		given(storeReportRepository.findById(50L)).willReturn(Optional.of(report));

		adminReportService.reviewReport(50L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(storeBusinessHourRepository).deleteByStoreId(3L);
		ArgumentCaptor<List<StoreBusinessHour>> captor = ArgumentCaptor.forClass(List.class);
		verify(storeBusinessHourRepository).saveAll(captor.capture());
		assertThat(captor.getValue()).hasSize(2);
		assertThat(captor.getValue().get(0).getOpenTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(captor.getValue().get(1).isClosed()).isTrue();
		verify(report).approve();
		verify(reportNotificationService).notifyReportResult(
			eq(reporter), eq(store), eq(50L), eq(ReportKind.STORE_REPORT),
			eq(ReportNotificationAction.APPROVED), anyString());
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_ADDRESS ACCEPT: Store.update 호출로 주소·위치·region 갱신")
	void storeReport_modBakeryAddress_accept_updatesStore() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(60L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_ADDRESS);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"address\":\"서울 강남구 테헤란로 1\","
				+ "\"roadAddress\":\"서울 강남구 테헤란로 1\","
				+ "\"placeName\":\"테스트 빵집\","
				+ "\"latitude\":37.5,\"longitude\":127.05,\"reason\":null}");
		given(storeReportRepository.findById(60L)).willReturn(Optional.of(report));

		adminReportService.reviewReport(60L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(store).update(
			eq(null),
			eq("서울 강남구 테헤란로 1"),
			any(),
			eq("서울"),
			eq("강남구"),
			eq("테헤란로"),
			eq(null),
			eq(null),
			eq(null));
		verify(report).approve();
	}

	@Test
	@DisplayName("StoreReport MOD_BAKERY_IMG ACCEPT: delete + add 이미지 처리 + S3 copy/delete")
	void storeReport_modBakeryImg_accept_addAndDeleteImages() {
		Store store = mock(Store.class);
		given(store.getId()).willReturn(5L);
		Member reporter = mock(Member.class);

		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(70L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BAKERY_IMG);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMessage()).willReturn(
			"{\"deleteImageIds\":[100,101],"
				+ "\"addImages\":[{\"fileName\":\"a.jpg\",\"contentType\":\"image/jpeg\","
				+ "\"awsKey\":\"reports/stores/5/members/9/images/key-a\"}],"
				+ "\"reason\":null}");
		given(storeReportRepository.findById(70L)).willReturn(Optional.of(report));

		StoreImageFile existing1 = mock(StoreImageFile.class);
		given(existing1.getAwsKey()).willReturn("stores/5/old1");
		StoreImageFile existing2 = mock(StoreImageFile.class);
		given(existing2.getAwsKey()).willReturn("stores/5/old2");
		given(storeImageFileRepository.findByIdInAndStoreId(List.of(100L, 101L), 5L))
			.willReturn(List.of(existing1, existing2));
		given(storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(5L))
			.willReturn(List.of());

		StoreReportImageFile src = mock(StoreReportImageFile.class);
		given(src.getAwsKey()).willReturn("reports/stores/5/members/9/images/key-a");
		given(src.getName()).willReturn("a.jpg");
		given(src.getMimeType()).willReturn(MimeType.IMAGE_JPEG);
		given(storeReportImageFileRepository.findByStoreReportIdIn(List.of(70L)))
			.willReturn(List.of(src));

		adminReportService.reviewReport(70L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(s3Service).deleteObject("stores/5/old1");
		verify(s3Service).deleteObject("stores/5/old2");
		verify(storeImageFileRepository).deleteAll(List.of(existing1, existing2));
		verify(s3Service).copyObject("reports/stores/5/members/9/images/key-a", "stores/5/key-a");
		verify(s3Service).deleteObject("reports/stores/5/members/9/images/key-a");
		verify(storeImageFileRepository).saveAll(anyList());
		verify(report).approve();
	}

	@Test
	@DisplayName("StoreReport MOD_BREAD ACCEPT: menu.price 갱신 + 이미지 add/delete + S3 처리")
	void storeReport_modBread_accept_updatesMenuAndImages() {
		Store store = mock(Store.class);
		Member reporter = mock(Member.class);
		Menu menu = mock(Menu.class);
		given(menu.getId()).willReturn(200L);

		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(80L);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		given(report.getRequestType()).willReturn(StoreReportType.MOD_BREAD);
		given(report.getStore()).willReturn(store);
		given(report.getMember()).willReturn(reporter);
		given(report.getMenu()).willReturn(menu);
		given(report.getMessage()).willReturn(
			"{\"price\":4500,\"deleteImageIds\":[301],"
				+ "\"addImages\":[{\"fileName\":\"m.jpg\",\"contentType\":\"image/jpeg\","
				+ "\"awsKey\":\"reports/stores/6/menus/200/members/9/key-m\"}],"
				+ "\"reason\":null}");
		given(storeReportRepository.findById(80L)).willReturn(Optional.of(report));

		MenuImageFile existing = mock(MenuImageFile.class);
		given(existing.getAwsKey()).willReturn("menus/200/oldmenu");
		given(menuImageFileRepository.findByIdInAndMenuId(List.of(301L), 200L))
			.willReturn(List.of(existing));
		given(menuImageFileRepository.findImagesByMenuId(200L)).willReturn(List.of());

		StoreReportImageFile src = mock(StoreReportImageFile.class);
		given(src.getAwsKey()).willReturn("reports/stores/6/menus/200/members/9/key-m");
		given(src.getName()).willReturn("m.jpg");
		given(src.getMimeType()).willReturn(MimeType.IMAGE_JPEG);
		given(storeReportImageFileRepository.findByStoreReportIdIn(List.of(80L)))
			.willReturn(List.of(src));

		adminReportService.reviewReport(80L, new AdminReportReviewRequest("STORE_REPORT", "ACCEPT"));

		verify(menu).update(null, 4500, null, null);
		verify(s3Service).deleteObject("menus/200/oldmenu");
		verify(s3Service).copyObject("reports/stores/6/menus/200/members/9/key-m", "menus/200/key-m");
		verify(s3Service).deleteObject("reports/stores/6/menus/200/members/9/key-m");
		verify(report).approve();
	}

	@Test
	@DisplayName("UserReport ACCEPT: 신고 상태만 승인 처리하고 제보 알림은 발송하지 않는다")
	void userReport_accept_updatesStatusOnly() {
		UserReport report = mock(UserReport.class);
		given(report.getStatus()).willReturn(UserReportStatus.PENDING);
		given(userReportRepository.findById(31L)).willReturn(Optional.of(report));

		adminReportService.reviewReport(31L, new AdminReportReviewRequest("USER_REPORT", "ACCEPT"));

		verify(report).accept();
		verify(report, never()).reject();
		verify(reportNotificationService, never()).notifyReportResult(
			any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("UserReport REJECT: 신고 상태만 거절 처리하고 제보 알림은 발송하지 않는다")
	void userReport_reject_updatesStatusOnly() {
		UserReport report = mock(UserReport.class);
		given(report.getStatus()).willReturn(UserReportStatus.PENDING);
		given(userReportRepository.findById(32L)).willReturn(Optional.of(report));

		adminReportService.reviewReport(32L, new AdminReportReviewRequest("USER_REPORT", "REJECT"));

		verify(report).reject();
		verify(report, never()).accept();
		verify(reportNotificationService, never()).notifyReportResult(
			any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("이미 처리된 제보는 다시 리뷰할 수 없다")
	void reviewReport_alreadyReviewed_throws() {
		StoreReport report = mock(StoreReport.class);
		given(report.getStatus()).willReturn(StoreReportStatus.ACCEPTED);
		given(storeReportRepository.findById(99L)).willReturn(Optional.of(report));

		assertThatThrownBy(() -> adminReportService.reviewReport(99L,
			new AdminReportReviewRequest("STORE_REPORT", "ACCEPT")))
			.isInstanceOf(AdminException.class)
			.hasMessageContaining(AdminErrorCode.REPORT_ALREADY_REVIEWED.getMessage());

		verify(report, never()).approve();
		verify(reportNotificationService, never()).notifyReportResult(
			any(), any(), any(), any(), any(), anyString());
	}

	@Test
	@DisplayName("kind가 잘못되면 예외를 던진다")
	void reviewReport_invalidKind_throws() {
		assertThatThrownBy(() -> adminReportService.reviewReport(1L,
			new AdminReportReviewRequest("UNKNOWN", "ACCEPT")))
			.isInstanceOf(AdminException.class);
	}

	@Test
	@DisplayName("action이 ACCEPT/REJECT 외이면 예외를 던진다")
	void reviewReport_invalidAction_throws() {
		assertThatThrownBy(() -> adminReportService.reviewReport(1L,
			new AdminReportReviewRequest("STORE_REPORT", "MAYBE")))
			.isInstanceOf(AdminException.class);
	}
}
