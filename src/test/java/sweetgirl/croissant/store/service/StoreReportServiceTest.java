package sweetgirl.croissant.store.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.request.MenuEditReportRequest;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.code.StoreReportErrorCode;
import sweetgirl.croissant.store.dto.request.ReportImageRequest;
import sweetgirl.croissant.store.dto.request.StoreAddressReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest.BusinessHourEntry;
import sweetgirl.croissant.store.dto.request.StoreImageReportRequest;
import sweetgirl.croissant.store.dto.response.StoreReportResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreReport;
import sweetgirl.croissant.store.enums.StoreReportStatus;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.exception.StoreReportException;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportImageFileRepository;
import sweetgirl.croissant.store.repository.StoreReportRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@ExtendWith(MockitoExtension.class)
class StoreReportServiceTest {

	@Mock
	S3Service s3Service;
	@Mock
	ObjectMapper objectMapper;
	@Mock
	MemberReader memberReader;
	@Mock
	MenuRepository menuRepository;
	@Mock
	MenuImageFileRepository menuImageFileRepository;
	@Mock
	StoreRepository storeRepository;
	@Mock
	StoreImageFileRepository storeImageFileRepository;
	@Mock
	StoreReportRepository storeReportRepository;
	@Mock
	StoreReportImageFileRepository storeReportImageFileRepository;

	@InjectMocks
	StoreReportService storeReportService;

	// ───────────────────────── createImageReport ─────────────────────────

	@Test
	@DisplayName("createImageReport: 삭제·추가 둘 다 비어있으면 REPORT_CONTENT_EMPTY를 던진다")
	void createImageReport_emptyRequest_throwsContentEmpty() {
		StoreImageReportRequest request = new StoreImageReportRequest(List.of(), List.of(), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));

		assertThatThrownBy(() -> storeReportService.createImageReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_CONTENT_EMPTY.getMessage());
	}

	@Test
	@DisplayName("createImageReport: deleteImageIds가 해당 가게 소유가 아니면 REPORT_IMAGE_NOT_OWNED를 던진다")
	void createImageReport_notOwned_throwsNotOwned() {
		StoreImageReportRequest request = new StoreImageReportRequest(List.of(1L, 2L), List.of(), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeImageFileRepository.countByIdInAndStoreId(List.of(1L, 2L), 1L)).willReturn(1L);

		assertThatThrownBy(() -> storeReportService.createImageReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_IMAGE_NOT_OWNED.getMessage());
	}

	@Test
	@DisplayName("createImageReport: store가 없으면 StoreException을 던진다")
	void createImageReport_storeNotFound_throwsException() {
		StoreImageReportRequest request = new StoreImageReportRequest(List.of(1L), List.of(), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeImageFileRepository.countByIdInAndStoreId(List.of(1L), 1L)).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> storeReportService.createImageReport(1L, 1L, request))
			.isInstanceOf(StoreException.class)
			.hasMessageContaining(StoreErrorCode.STORE_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("createImageReport: 삭제만 있는 정상 요청이면 StoreReport를 저장하고 imageFile은 저장하지 않는다")
	void createImageReport_deleteOnly_success() throws JsonProcessingException {
		StoreImageReportRequest request = new StoreImageReportRequest(List.of(1L), List.of(), "사진 잘못됨");
		StoreReport savedReport = savedReportMock(10L);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeImageFileRepository.countByIdInAndStoreId(List.of(1L), 1L)).willReturn(1L);
		given(storeRepository.findById(1L)).willReturn(Optional.of(mock(Store.class)));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createImageReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(10L);
		assertThat(result.status()).isEqualTo(StoreReportStatus.PENDING);
		verify(storeReportImageFileRepository, never()).saveAll(anyList());
	}

	@Test
	@DisplayName("createImageReport: addImages가 있으면 StoreReportImageFile도 함께 저장한다")
	void createImageReport_withAddImages_savesImageFiles() throws JsonProcessingException {
		ReportImageRequest img = new ReportImageRequest("test.jpg", "image/jpeg",
			"reports/stores/1/members/1/images/uuid.jpg");
		StoreImageReportRequest request = new StoreImageReportRequest(List.of(), List.of(img), null);
		StoreReport savedReport = savedReportMock(10L);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeRepository.findById(1L)).willReturn(Optional.of(mock(Store.class)));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createImageReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(10L);
		verify(storeReportImageFileRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("createImageReport: addImages의 awsKey prefix가 올바르지 않으면 REPORT_INVALID_AWS_KEY를 던진다")
	void createImageReport_invalidAwsKeyPrefix_throwsInvalidKey() {
		ReportImageRequest img = new ReportImageRequest("test.jpg", "image/jpeg",
			"wrong/path/uuid.jpg");
		StoreImageReportRequest request = new StoreImageReportRequest(List.of(), List.of(img), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));

		assertThatThrownBy(() -> storeReportService.createImageReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_INVALID_AWS_KEY.getMessage());
	}

	// ───────────────────────── createBusinessHoursReport ─────────────────────────

	@Test
	@DisplayName("createBusinessHoursReport: closed=false인데 openTime이 null이면 REPORT_TIME_REQUIRED를 던진다")
	void createBusinessHoursReport_missingOpenTime_throwsTimeRequired() {
		var request = new StoreBusinessHoursReportRequest(
			List.of(new BusinessHourEntry(DayOfWeek.MONDAY, false, null, "18:00")), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));

		assertThatThrownBy(() -> storeReportService.createBusinessHoursReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_TIME_REQUIRED.getMessage());
	}

	@Test
	@DisplayName("createBusinessHoursReport: closed=false인데 closeTime이 null이면 REPORT_TIME_REQUIRED를 던진다")
	void createBusinessHoursReport_missingCloseTime_throwsTimeRequired() {
		var request = new StoreBusinessHoursReportRequest(
			List.of(new BusinessHourEntry(DayOfWeek.MONDAY, false, "09:00", null)), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));

		assertThatThrownBy(() -> storeReportService.createBusinessHoursReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_TIME_REQUIRED.getMessage());
	}

	@Test
	@DisplayName("createBusinessHoursReport: openTime이 closeTime보다 늦으면 REPORT_INVALID_TIME_RANGE를 던진다")
	void createBusinessHoursReport_invalidTimeRange_throwsInvalidRange() {
		var request = new StoreBusinessHoursReportRequest(
			List.of(new BusinessHourEntry(DayOfWeek.MONDAY, false, "18:00", "09:00")), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));

		assertThatThrownBy(() -> storeReportService.createBusinessHoursReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_INVALID_TIME_RANGE.getMessage());
	}

	@Test
	@DisplayName("createBusinessHoursReport: closed=true이면 openTime·closeTime이 null이어도 통과한다")
	void createBusinessHoursReport_closedDay_skipsTimeValidation() throws JsonProcessingException {
		var request = new StoreBusinessHoursReportRequest(
			List.of(new BusinessHourEntry(DayOfWeek.SUNDAY, true, null, null)), null);
		StoreReport savedReport = savedReportMock(10L);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeRepository.findById(1L)).willReturn(Optional.of(mock(Store.class)));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createBusinessHoursReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(10L);
		assertThat(result.status()).isEqualTo(StoreReportStatus.PENDING);
	}

	@Test
	@DisplayName("createBusinessHoursReport: 정상 요청이면 StoreReport를 저장하고 응답을 반환한다")
	void createBusinessHoursReport_validRequest_success() throws JsonProcessingException {
		var request = new StoreBusinessHoursReportRequest(
			List.of(new BusinessHourEntry(DayOfWeek.MONDAY, false, "09:00", "18:00")), "수정 요청");
		StoreReport savedReport = savedReportMock(10L);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeRepository.findById(1L)).willReturn(Optional.of(mock(Store.class)));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createBusinessHoursReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(10L);
		assertThat(result.status()).isEqualTo(StoreReportStatus.PENDING);
	}

	// ───────────────────────── createAddressReport ─────────────────────────

	@Test
	@DisplayName("createAddressReport: store가 없으면 StoreException을 던진다")
	void createAddressReport_storeNotFound_throwsException() {
		var request = new StoreAddressReportRequest("서울시 강남구", "강남대로 1", "뚜레쥬르", 37.5, 127.0, null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeRepository.findById(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> storeReportService.createAddressReport(1L, 1L, request))
			.isInstanceOf(StoreException.class)
			.hasMessageContaining(StoreErrorCode.STORE_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("createAddressReport: 정상 요청이면 StoreReport를 저장하고 응답을 반환한다")
	void createAddressReport_validRequest_success() throws JsonProcessingException {
		var request = new StoreAddressReportRequest("서울시 강남구", "강남대로 1", "뚜레쥬르", 37.5, 127.0, "주소 변경");
		StoreReport savedReport = savedReportMock(10L);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(storeRepository.findById(1L)).willReturn(Optional.of(mock(Store.class)));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createAddressReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(10L);
		assertThat(result.status()).isEqualTo(StoreReportStatus.PENDING);
	}

	// ───────────────────────── createMenuEditReport ─────────────────────────

	@Test
	@DisplayName("createMenuEditReport: menu가 없으면 MenuException을 던진다")
	void createMenuEditReport_menuNotFound_throwsException() {
		MenuEditReportRequest request = new MenuEditReportRequest(6500, null, null, null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> storeReportService.createMenuEditReport(1L, 1L, request))
			.isInstanceOf(MenuException.class)
			.hasMessageContaining(MenuErrorCode.MENU_NOT_FOUND.getMessage());
	}

	@Test
	@DisplayName("createMenuEditReport: price·deleteImageIds·addImages 모두 비어있으면 REPORT_EDIT_CONTENT_EMPTY를 던진다")
	void createMenuEditReport_allEmpty_throwsContentEmpty() {
		MenuEditReportRequest request = new MenuEditReportRequest(null, List.of(), List.of(), null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.of(mock(Menu.class)));

		assertThatThrownBy(() -> storeReportService.createMenuEditReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_EDIT_CONTENT_EMPTY.getMessage());
	}

	@Test
	@DisplayName("createMenuEditReport: deleteImageIds가 해당 menu 소유가 아니면 REPORT_MENU_IMAGE_NOT_OWNED를 던진다")
	void createMenuEditReport_deleteImageNotOwned_throwsNotOwned() {
		MenuEditReportRequest request = new MenuEditReportRequest(null, List.of(1L, 2L), null, null);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.of(mock(Menu.class)));
		given(menuImageFileRepository.countByIdInAndMenuId(List.of(1L, 2L), 1L)).willReturn(1L);

		assertThatThrownBy(() -> storeReportService.createMenuEditReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_MENU_IMAGE_NOT_OWNED.getMessage());
	}

	@Test
	@DisplayName("createMenuEditReport: addImages의 awsKey prefix가 올바르지 않으면 REPORT_INVALID_AWS_KEY를 던진다")
	void createMenuEditReport_invalidAwsKeyPrefix_throwsInvalidKey() {
		ReportImageRequest img = new ReportImageRequest("menu.jpg", "image/jpeg", "wrong/path/uuid");
		MenuEditReportRequest request = new MenuEditReportRequest(null, null, List.of(img), null);
		Menu menu = mock(Menu.class);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(7L);
		given(menu.getStore()).willReturn(store);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

		assertThatThrownBy(() -> storeReportService.createMenuEditReport(1L, 1L, request))
			.isInstanceOf(StoreReportException.class)
			.hasMessageContaining(StoreReportErrorCode.REPORT_INVALID_AWS_KEY.getMessage());
	}

	@Test
	@DisplayName("createMenuEditReport: 허용하지 않는 MIME 타입이면 예외를 던진다")
	void createMenuEditReport_invalidMimeType_throwsException() {
		ReportImageRequest img = new ReportImageRequest("menu.gif", "image/gif",
			"reports/stores/7/menus/1/uuid");
		MenuEditReportRequest request = new MenuEditReportRequest(null, null, List.of(img), null);
		Menu menu = mock(Menu.class);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(7L);
		given(menu.getStore()).willReturn(store);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

		assertThatThrownBy(() -> storeReportService.createMenuEditReport(1L, 1L, request))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("createMenuEditReport: 정상 요청이면 StoreReport를 저장하고 PENDING 상태를 반환한다")
	void createMenuEditReport_validRequest_returnsPendingStatus() throws JsonProcessingException {
		MenuEditReportRequest request = new MenuEditReportRequest(6500, null, null, "가격 변경");
		StoreReport savedReport = savedReportMock(4L);
		Menu menu = mock(Menu.class);
		Store store = mock(Store.class);
		given(menu.getStore()).willReturn(store);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createMenuEditReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(4L);
		assertThat(result.status()).isEqualTo(StoreReportStatus.PENDING);
		verify(storeReportImageFileRepository, never()).saveAll(anyList());
	}

	@Test
	@DisplayName("createMenuEditReport: addImages가 있으면 StoreReportImageFile도 저장한다")
	void createMenuEditReport_withAddImages_savesImageFiles() throws JsonProcessingException {
		ReportImageRequest img = new ReportImageRequest("menu.jpg", "image/jpeg",
			"reports/stores/7/menus/1/uuid");
		MenuEditReportRequest request = new MenuEditReportRequest(null, null, List.of(img), null);
		StoreReport savedReport = savedReportMock(4L);
		Menu menu = mock(Menu.class);
		Store store = mock(Store.class);
		given(store.getId()).willReturn(7L);
		given(menu.getStore()).willReturn(store);

		given(memberReader.getMember(1L)).willReturn(mock(Member.class));
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		StoreReportResponse result = storeReportService.createMenuEditReport(1L, 1L, request);

		assertThat(result.reportId()).isEqualTo(4L);
		verify(storeReportImageFileRepository).saveAll(anyList());
	}

	@Test
	@DisplayName("createMenuEditReport: 정상 생성 시 member가 로그인 사용자로 전달된다")
	void createMenuEditReport_validRequest_memberSaved() throws JsonProcessingException {
		MenuEditReportRequest request = new MenuEditReportRequest(6500, null, null, null);
		Member member = mock(Member.class);
		StoreReport savedReport = savedReportMock(4L);
		Menu menu = mock(Menu.class);
		Store store = mock(Store.class);
		given(menu.getStore()).willReturn(store);

		given(memberReader.getMember(1L)).willReturn(member);
		given(menuRepository.findById(1L)).willReturn(Optional.of(menu));
		given(objectMapper.writeValueAsString(request)).willReturn("{}");
		given(storeReportRepository.save(any())).willReturn(savedReport);

		storeReportService.createMenuEditReport(1L, 1L, request);

		verify(storeReportRepository).save(argThat(r -> r.getMember() == member));
	}

	private StoreReport savedReportMock(Long id) {
		StoreReport report = mock(StoreReport.class);
		given(report.getId()).willReturn(id);
		given(report.getStatus()).willReturn(StoreReportStatus.PENDING);
		return report;
	}

}
