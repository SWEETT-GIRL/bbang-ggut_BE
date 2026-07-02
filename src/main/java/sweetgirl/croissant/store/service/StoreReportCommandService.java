package sweetgirl.croissant.store.service;

import java.time.LocalTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.contribution.entity.ContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReportImageFile;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReportImageFile;
import sweetgirl.croissant.contribution.enums.ContributionReportType;
import sweetgirl.croissant.contribution.enums.ContributionTargetType;
import sweetgirl.croissant.contribution.repository.ContributionReportRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.MenuContributionReportRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.request.MenuEditReportRequest;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.notification.service.AdminReportNotificationPublisher;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.code.StoreReportErrorCode;
import sweetgirl.croissant.store.dto.request.ReportImageRequest;
import sweetgirl.croissant.store.dto.request.ReportPresignedUrlRequest;
import sweetgirl.croissant.store.dto.request.StoreAddressReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest;
import sweetgirl.croissant.store.dto.request.StoreImageReportRequest;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfo;
import sweetgirl.croissant.store.dto.response.PresignedUrlInfoResponse;
import sweetgirl.croissant.store.dto.response.StoreReportResponse;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.exception.StoreReportException;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional
public class StoreReportCommandService {

	private final S3Service s3Service;
	private final ObjectMapper objectMapper;
	private final MemberReader memberReader;
	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final StoreRepository storeRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final ContributionReportRepository contributionReportRepository;
	private final StoreContributionReportRepository storeContributionReportRepository;
	private final MenuContributionReportRepository menuContributionReportRepository;
	private final StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	private final MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	private final AdminReportNotificationPublisher adminReportNotificationPublisher;

	// STORE - mimeType 개수만큼 S3에 PresignedUrl 발급 요청
	public PresignedUrlInfoResponse generateStoreReportPresignedUrls(Long memberId, Long storeId,
			ReportPresignedUrlRequest request) {

		memberReader.getMember(memberId);
		storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		if (request.mimeTypes().size() > 5) { // 최대 5장 제한
			throw new StoreReportException(StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED);
		}

		List<PresignedUrlInfo> urls = request.mimeTypes().stream()
			.map(mimeType -> {
				String path = S3PathGenerator.getReportStoreImagePath(memberId, storeId);
				PresignedUrlResponse response = s3Service.generatePresignedUrl(path, mimeType);
				return new PresignedUrlInfo(response.uploadUrl(), response.awsKey());
			}).toList();

		return new PresignedUrlInfoResponse(urls);
	}

	// MENU - mimeType 개수만큼 S3에 PresignedUrl 발급 요청
	public PresignedUrlInfoResponse generateMenuReportPresignedUrls(Long memberId, Long menuId,
			ReportPresignedUrlRequest request) {

		memberReader.getMember(memberId);
		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		if (request.mimeTypes().size() > 5) { // 최대 5장 제한
			throw new StoreReportException(StoreReportErrorCode.REPORT_IMAGE_COUNT_EXCEEDED);
		}

		List<PresignedUrlInfo> urls = request.mimeTypes().stream()
			.map(mimeType -> {
				Long storeId = menu.getStore().getId();
				String path = S3PathGenerator.getReportMenuPath(memberId, storeId, menuId);
				PresignedUrlResponse response = s3Service.generatePresignedUrl(path, mimeType);
				return new PresignedUrlInfo(response.uploadUrl(), response.awsKey());
			}).toList();

		return new PresignedUrlInfoResponse(urls);
	}

	// 가게 이미지 추가/삭제 제보 — 삭제·추가 대상 검증 후 StoreReport 및 이미지 파일 저장
	public StoreReportResponse createImageReport(Long memberId, Long storeId, StoreImageReportRequest request) {
		Member member = memberReader.getMember(memberId);

		boolean hasDelete = request.deleteImageIds() != null && !request.deleteImageIds().isEmpty();
		boolean hasAdd = request.addImages() != null && !request.addImages().isEmpty();
		if (!hasDelete && !hasAdd) {
			throw new StoreReportException(StoreReportErrorCode.REPORT_CONTENT_EMPTY);
		}

		if (hasDelete) {
			long count = storeImageFileRepository.countByIdInAndStoreId(request.deleteImageIds(), storeId);
			if (count != request.deleteImageIds().size()) {
				throw new StoreReportException(StoreReportErrorCode.REPORT_IMAGE_NOT_OWNED);
			}
		}

		if (hasAdd) {
			String expectedPrefix = "reports/stores/" + storeId + "/";
			for (ReportImageRequest img : request.addImages()) {
				if (!img.awsKey().startsWith(expectedPrefix)) {
					throw new StoreReportException(StoreReportErrorCode.REPORT_INVALID_AWS_KEY);
				}
			}
		}

		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		ContributionReport contributionReport = saveContributionReport(
			member, ContributionTargetType.STORE, ContributionReportType.UPDATE);
		StoreContributionReport report = storeContributionReportRepository.save(StoreContributionReport.builder()
			.contributionReport(contributionReport)
			.store(store)
			.requestType(StoreReportType.MOD_BAKERY_IMG.name())
			.message(serializeMessage(request))
			.reason(request.reason())
			.build());

		if (hasAdd) {
			List<StoreContributionReportImageFile> imageFiles = request.addImages().stream()
				.map(img -> StoreContributionReportImageFile.builder()
					.storeContributionReport(report)
					.name(img.fileName())
					.mimeType(MimeType.fromString(img.contentType()))
					.awsKey(img.awsKey())
					.build())
				.toList();
			storeContributionReportImageFileRepository.saveAll(imageFiles);
		}

		adminReportNotificationPublisher.publishStoreContributionReportCreated(report);
		return new StoreReportResponse(contributionReport.getId(), report.getStatus());
	}

	// 가게 영업시간 수정 제보 — 오픈·마감 시간 유효성 검증 후 StoreReport 저장
	public StoreReportResponse createBusinessHoursReport(Long memberId, Long storeId,
			StoreBusinessHoursReportRequest request) {
		Member member = memberReader.getMember(memberId);

		for (StoreBusinessHoursReportRequest.BusinessHourEntry hour : request.hours()) {
			if (!hour.closed()) {
				if (hour.openTime() == null || hour.closeTime() == null) {
					throw new StoreReportException(StoreReportErrorCode.REPORT_TIME_REQUIRED);
				}
				if (!LocalTime.parse(hour.openTime()).isBefore(LocalTime.parse(hour.closeTime()))) {
					throw new StoreReportException(StoreReportErrorCode.REPORT_INVALID_TIME_RANGE);
				}
			}
		}

		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		ContributionReport contributionReport = saveContributionReport(
			member, ContributionTargetType.STORE, ContributionReportType.UPDATE);
		StoreContributionReport report = storeContributionReportRepository.save(StoreContributionReport.builder()
			.contributionReport(contributionReport)
			.store(store)
			.requestType(StoreReportType.MOD_BAKERY_TIME.name())
			.message(serializeMessage(request))
			.reason(request.reason())
			.build());

		adminReportNotificationPublisher.publishStoreContributionReportCreated(report);
		return new StoreReportResponse(contributionReport.getId(), report.getStatus());
	}

	// 가게 주소 수정 제보 — StoreReport 저장
	public StoreReportResponse createAddressReport(Long memberId, Long storeId, StoreAddressReportRequest request) {
		Member member = memberReader.getMember(memberId);

		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		ContributionReport contributionReport = saveContributionReport(
			member, ContributionTargetType.STORE, ContributionReportType.UPDATE);
		StoreContributionReport report = storeContributionReportRepository.save(StoreContributionReport.builder()
			.contributionReport(contributionReport)
			.store(store)
			.requestType(StoreReportType.MOD_BAKERY_ADDRESS.name())
			.address(request.address())
			.roadAddress(request.roadAddress())
			.location(toPoint(request.latitude(), request.longitude()))
			.message(serializeMessage(request))
			.reason(request.reason())
			.build());

		adminReportNotificationPublisher.publishStoreContributionReportCreated(report);
		return new StoreReportResponse(contributionReport.getId(), report.getStatus());
	}

	// 메뉴 수정 제보 — 가격·이미지 삭제·이미지 추가를 하나의 제보로 접수
	public StoreReportResponse createMenuEditReport(Long memberId, Long menuId, MenuEditReportRequest request) {
		Member member = memberReader.getMember(memberId);

		Menu menu = menuRepository.findById(menuId)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_NOT_FOUND));

		boolean hasPrice = request.price() != null;
		boolean hasDelete = request.deleteImageIds() != null && !request.deleteImageIds().isEmpty();
		boolean hasAdd = request.addImages() != null && !request.addImages().isEmpty();
		if (!hasPrice && !hasDelete && !hasAdd) {
			throw new StoreReportException(StoreReportErrorCode.REPORT_EDIT_CONTENT_EMPTY);
		}

		if (hasDelete) {
			long count = menuImageFileRepository.countByIdInAndMenuId(request.deleteImageIds(), menuId);
			if (count != request.deleteImageIds().size()) {
				throw new StoreReportException(StoreReportErrorCode.REPORT_MENU_IMAGE_NOT_OWNED);
			}
		}

		if (hasAdd) {
			Long storeId = menu.getStore().getId();
			String expectedPrefix = "reports/stores/" + storeId + "/menus/" + menuId + "/";
			for (ReportImageRequest img : request.addImages()) {
				MimeType.fromString(img.contentType());
				if (!img.awsKey().startsWith(expectedPrefix)) {
					throw new StoreReportException(StoreReportErrorCode.REPORT_INVALID_AWS_KEY);
				}
			}
		}

		ContributionReport contributionReport = saveContributionReport(
			member, ContributionTargetType.MENU, ContributionReportType.UPDATE);
		MenuContributionReport report = menuContributionReportRepository.save(MenuContributionReport.builder()
			.contributionReport(contributionReport)
			.store(menu.getStore())
			.menu(menu)
			.requestType(StoreReportType.MOD_BREAD.name())
			.price(request.price())
			.message(serializeMessage(request))
			.build());

		if (hasAdd) {
			List<MenuContributionReportImageFile> imageFiles = request.addImages().stream()
				.map(img -> MenuContributionReportImageFile.builder()
					.menuContributionReport(report)
					.name(img.fileName())
					.mimeType(MimeType.fromString(img.contentType()))
					.awsKey(img.awsKey())
					.build())
				.toList();
			menuContributionReportImageFileRepository.saveAll(imageFiles);
		}

		adminReportNotificationPublisher.publishMenuContributionReportCreated(report);
		return new StoreReportResponse(contributionReport.getId(), report.getStatus());
	}

	private String serializeMessage(Object request) {
		try {
			return objectMapper.writeValueAsString(request);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private ContributionReport saveContributionReport(
			Member member,
			ContributionTargetType targetType,
			ContributionReportType reportType) {
		return contributionReportRepository.save(ContributionReport.builder()
			.member(member)
			.targetType(targetType)
			.reportType(reportType)
			.build());
	}

	private org.locationtech.jts.geom.Point toPoint(double latitude, double longitude) {
		org.locationtech.jts.geom.GeometryFactory geometryFactory = new org.locationtech.jts.geom.GeometryFactory(
			new org.locationtech.jts.geom.PrecisionModel(), 4326);
		return geometryFactory.createPoint(new org.locationtech.jts.geom.Coordinate(longitude, latitude));
	}

}
