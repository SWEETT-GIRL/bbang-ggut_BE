package sweetgirl.croissant.admin.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.code.AdminErrorCode;
import sweetgirl.croissant.admin.exception.AdminException;
import sweetgirl.croissant.contribution.entity.MenuContributionReport;
import sweetgirl.croissant.contribution.entity.MenuContributionReportImageFile;
import sweetgirl.croissant.contribution.entity.StoreContributionReport;
import sweetgirl.croissant.contribution.entity.StoreContributionReportImageFile;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.external.opensearch.event.StoreAddressChangedEvent;
import sweetgirl.croissant.external.opensearch.event.StoreCreatedEvent;
import sweetgirl.croissant.external.opensearch.event.StoreMenusChangedEvent;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuImageFile;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.store.AddressParser;
import sweetgirl.croissant.store.dto.request.ReportImageRequest;
import sweetgirl.croissant.store.dto.request.StoreAddressReportRequest;
import sweetgirl.croissant.store.dto.request.StoreBusinessHoursReportRequest;
import sweetgirl.croissant.store.dto.request.StoreImageReportRequest;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.entity.StoreImageFile;
import sweetgirl.croissant.store.enums.StoreCategory;
import sweetgirl.croissant.store.enums.StoreReportType;
import sweetgirl.croissant.store.enums.StoreSource;
import sweetgirl.croissant.store.repository.StoreBusinessHourRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

// 제보 승인/거절 시 실제 부수효과(Store 생성, 영업시간·주소·이미지 반영, S3 이동/정리)를 전담.
// 호출자(AdminReportCommandService)의 @Transactional 안에서 실행된다.
@Component
@RequiredArgsConstructor
public class ContributionReportProcessor {

	private static final Logger log = LoggerFactory.getLogger(ContributionReportProcessor.class);
	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private final StoreRepository storeRepository;
	private final StoreContributionReportImageFileRepository storeContributionReportImageFileRepository;
	private final StoreImageFileRepository storeImageFileRepository;
	private final StoreBusinessHourRepository storeBusinessHourRepository;
	private final MenuRepository menuRepository;
	private final MenuImageFileRepository menuImageFileRepository;
	private final MenuContributionReportImageFileRepository menuContributionReportImageFileRepository;
	private final S3Service s3Service;
	private final ObjectMapper objectMapper;
	private final ApplicationEventPublisher eventPublisher;
	private final ImageProcessingCallbackService imageProcessingCallbackService;

	// 메뉴 추가 승인 — 새 Menu 생성 + 첨부 이미지를 menus/{menuId}로 S3 이동 후 menu_image_file 등록
	public void applyMenuAddReportApprove(MenuContributionReport report) {
		Menu menu = menuRepository.save(report.toMenu());

		List<MenuContributionReportImageFile> reportImages = menuContributionReportImageFileRepository
			.findByMenuContributionReportIdIn(List.of(report.getId()));
		moveImagesToMenu(menu, reportImages);
		eventPublisher.publishEvent(new StoreMenusChangedEvent(menu.getStore().getId()));
	}

	// 메뉴 추가 거절 — temp S3 객체 정리 (DB 행은 이력 보관 위해 유지)
	public void cleanupMenuAddReportImages(MenuContributionReport report) {
		menuContributionReportImageFileRepository.findByMenuContributionReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	public Store applyStoreAddReportApprove(StoreContributionReport report) {
		if (report.getNaverPlaceId() != null && !report.getNaverPlaceId().isBlank()) {
			return storeRepository.findByNaverPlaceId(report.getNaverPlaceId())
				.orElseGet(() -> findOrCreateStoreFromAddReport(report));
		}
		return findOrCreateStoreFromAddReport(report);
	}

	// 가게 수정 제보 승인 — 요청 타입(영업시간/주소/이미지)별로 반영
	public void applyStoreReportApprove(StoreContributionReport report, StoreReportType type) {
		switch (type) {
			case MOD_BAKERY_TIME -> applyBusinessHoursUpdate(report);
			case MOD_BAKERY_ADDRESS -> applyAddressUpdate(report);
			case MOD_BAKERY_IMG -> applyStoreImageChange(report);
			case MOD_BREAD -> throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
			default -> throw new AdminException(AdminErrorCode.REPORT_INVALID_KIND);
		}
	}

	// 가게 수정 거절 — 첨부 이미지 reports/ 객체 정리 (DB 행은 이력 유지)
	public void cleanupStoreReportImages(StoreContributionReport report) {
		storeContributionReportImageFileRepository.findByStoreContributionReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	// 메뉴 수정 제보 승인 — menu.price 갱신 + 이미지 add/delete 적용
	public void applyMenuReportApprove(MenuContributionReport report) {
		Menu menu = report.getMenu();
		if (menu == null) {
			log.warn("applyMenuEdit: 메뉴 참조 없음 [reportId={}]", report.getId());
			return;
		}
		MenuEditPayload payload = parseMessage(report.getMessage(), MenuEditPayload.class);

		if (payload.price() != null) {
			menu.update(null, payload.price(), null, null);
		}

		if (payload.deleteImageIds() != null && !payload.deleteImageIds().isEmpty()) {
			List<MenuImageFile> toDelete = menuImageFileRepository.findByIdInAndMenuId(
				payload.deleteImageIds(), menu.getId());
			toDelete.forEach(img -> safeDelete(img.getAwsKey()));
			menuImageFileRepository.deleteAll(toDelete);
		}

		if (payload.addImages() != null && !payload.addImages().isEmpty()) {
			Map<String, MenuContributionReportImageFile> sourceByKey = menuContributionReportImageFileRepository
				.findByMenuContributionReportIdIn(List.of(report.getId())).stream()
				.collect(Collectors.toMap(MenuContributionReportImageFile::getAwsKey, img -> img, (a, b) -> a));

			int nextOrder = currentMaxMenuImageOrder(menu.getId()) + 1;
			List<MenuImageFile> newImages = new ArrayList<>();
			for (ReportImageRequest imageRequest : payload.addImages()) {
				MenuContributionReportImageFile sourceImage = sourceByKey.get(imageRequest.awsKey());
				if (sourceImage == null) {
					log.warn("applyMenuEdit: 대응 reports 이미지 없음 [reportId={}, key={}]",
						report.getId(), imageRequest.awsKey());
					continue;
				}
				String destKey = "menus/" + menu.getId() + "/" + filenameFromKey(sourceImage.getAwsKey());
				s3Service.copyObject(sourceImage.getAwsKey(), destKey);
				newImages.add(sourceImage.toMenuImage(menu, destKey, nextOrder++));
				safeDelete(sourceImage.getAwsKey());
			}
			imageProcessingCallbackService.applyPendingCallbacks(menuImageFileRepository.saveAll(newImages));
		}
		eventPublisher.publishEvent(new StoreMenusChangedEvent(menu.getStore().getId()));
	}

	public void cleanupMenuContributionImages(MenuContributionReport report) {
		menuContributionReportImageFileRepository.findByMenuContributionReportIdIn(List.of(report.getId()))
			.forEach(img -> safeDelete(img.getAwsKey()));
	}

	private Store findOrCreateStoreFromAddReport(StoreContributionReport report) {
		return findActiveStoreBySameNameAndAddress(report)
			.map(store -> {
				if (report.getNaverPlaceId() != null && !report.getNaverPlaceId().isBlank()
					&& store.getNaverPlaceId() == null) {
					store.markNaverMatched(report.getNaverPlaceId());
				}
				return store;
			})
			.orElseGet(() -> createStoreFromAddReport(report));
	}

	private Store createStoreFromAddReport(StoreContributionReport report) {
		String[] addr = AddressParser.parse(report.getAddress());
		Store store = Store.builder()
			.name(report.getName())
			.address(report.getAddress())
			.region1st(addr[0])
			.region2nd(addr[1])
			.region3rd(addr[2])
			.location(report.getLocation())
			.phone(report.getPhone())
			.naverPlaceId(report.getNaverPlaceId())
			.source(StoreSource.ADMIN_REGISTERED)
			.category(StoreCategory.WESTERN)
			.build();
		Store saved = storeRepository.save(store);
		eventPublisher.publishEvent(new StoreCreatedEvent(saved.getId()));
		return saved;
	}

	private Optional<Store> findActiveStoreBySameNameAndAddress(StoreContributionReport report) {
		String compactName = compactName(report.getName());
		String normalizedAddress = normalizeAddressForDuplicate(report.getAddress());
		if (compactName.isBlank() || normalizedAddress.isBlank()) {
			return Optional.empty();
		}
		return storeRepository.findActiveStoresByCompactName(compactName)
			.stream()
			.filter(store -> normalizedAddress.equals(normalizeAddressForDuplicate(store.getAddress())))
			.findFirst();
	}

	private String compactName(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase().replaceAll("\\s+", "");
	}

	private String normalizeAddressForDuplicate(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase()
			.replaceAll("(?:지하)?\\d+층", "")
			.replaceAll("\\d+호", "")
			.replaceAll("\\s+", "")
			.trim();
	}

	// 영업시간 제보 승인 — 기존 영업시간 전체 삭제 후 제보 내용으로 재삽입
	private void applyBusinessHoursUpdate(StoreContributionReport report) {
		StoreBusinessHoursReportRequest payload = parseMessage(report.getMessage(),
			StoreBusinessHoursReportRequest.class);
		Long storeId = report.getStore().getId();
		storeBusinessHourRepository.deleteByStoreId(storeId);

		storeBusinessHourRepository.saveAll(payload.toBusinessHours(report.getStore()));
	}

	// 주소 제보 승인 — Store 주소/위치/region 갱신
	private void applyAddressUpdate(StoreContributionReport report) {
		StoreAddressReportRequest payload = parseMessage(report.getMessage(), StoreAddressReportRequest.class);
		String[] addr = AddressParser.parse(payload.address());
		Point location = GEOMETRY_FACTORY.createPoint(new Coordinate(payload.longitude(), payload.latitude()));
		report.getStore().update(null, payload.address(), location, addr[0], addr[1], addr[2],
			null, null, null);
		eventPublisher.publishEvent(new StoreAddressChangedEvent(report.getStore().getId()));
	}

	// 가게 이미지 제보 승인 — deleteImageIds로 store_image_file·S3 삭제 + addImages는 stores/{storeId}로 이동 후 신규 등록
	private void applyStoreImageChange(StoreContributionReport report) {
		StoreImageReportRequest payload = parseMessage(report.getMessage(), StoreImageReportRequest.class);
		Store store = report.getStore();
		Long storeId = store.getId();

		if (payload.deleteImageIds() != null && !payload.deleteImageIds().isEmpty()) {
			List<StoreImageFile> toDelete = storeImageFileRepository.findByIdInAndStoreId(
				payload.deleteImageIds(), storeId);
			toDelete.forEach(img -> safeDelete(img.getAwsKey()));
			storeImageFileRepository.deleteAll(toDelete);
		}

		if (payload.addImages() != null && !payload.addImages().isEmpty()) {
			Map<String, StoreContributionReportImageFile> sourceByKey = storeContributionReportImageFileRepository
				.findByStoreContributionReportIdIn(List.of(report.getId())).stream()
				.collect(Collectors.toMap(StoreContributionReportImageFile::getAwsKey, img -> img, (a, b) -> a));

			int nextOrder = currentMaxStoreImageOrder(storeId) + 1;
			List<StoreImageFile> newImages = new ArrayList<>();
			for (ReportImageRequest imageRequest : payload.addImages()) {
				StoreContributionReportImageFile sourceImage = sourceByKey.get(imageRequest.awsKey());
				if (sourceImage == null) {
					log.warn("applyStoreImageChange: 대응 reports 이미지 없음 [reportId={}, key={}]",
						report.getId(), imageRequest.awsKey());
					continue;
				}
				String destKey = "stores/" + storeId + "/" + filenameFromKey(sourceImage.getAwsKey());
				s3Service.copyObject(sourceImage.getAwsKey(), destKey);
				newImages.add(sourceImage.toStoreImage(store, destKey, nextOrder++));
				safeDelete(sourceImage.getAwsKey());
			}
			imageProcessingCallbackService.applyPendingCallbacks(storeImageFileRepository.saveAll(newImages));
		}
	}

	private void moveImagesToMenu(Menu menu, List<MenuContributionReportImageFile> sources) {
		if (sources.isEmpty()) {
			return;
		}
		int order = 0;
		List<MenuImageFile> dest = new ArrayList<>();
		for (MenuContributionReportImageFile src : sources) {
			String destKey = "menus/" + menu.getId() + "/" + filenameFromKey(src.getAwsKey());
			s3Service.copyObject(src.getAwsKey(), destKey);
			dest.add(src.toMenuImage(menu, destKey, order++));
			safeDelete(src.getAwsKey());
		}
		imageProcessingCallbackService.applyPendingCallbacks(menuImageFileRepository.saveAll(dest));
	}

	private int currentMaxStoreImageOrder(Long storeId) {
		return storeImageFileRepository.findByStoreIdOrderByDisplayOrderAscIdAsc(storeId).stream()
			.mapToInt(StoreImageFile::getDisplayOrder)
			.max()
			.orElse(-1);
	}

	private int currentMaxMenuImageOrder(Long menuId) {
		return menuImageFileRepository.findImagesByMenuId(menuId).stream()
			.mapToInt(MenuImageFile::getDisplayOrder)
			.max()
			.orElse(-1);
	}

	// S3 삭제 실패는 트랜잭션 롤백 사유로 부적합 — 고아 파일로 남더라도 로그만 남기고 진행
	private void safeDelete(String awsKey) {
		try {
			s3Service.deleteObject(awsKey);
		} catch (RuntimeException e) {
			log.warn("S3 삭제 실패 [key={}]: {}", awsKey, e.getMessage());
		}
	}

	private String filenameFromKey(String awsKey) {
		int idx = awsKey.lastIndexOf('/');
		return idx >= 0 ? awsKey.substring(idx + 1) : awsKey;
	}

	private <T> T parseMessage(String message, Class<T> type) {
		try {
			return objectMapper.readValue(message, type);
		} catch (JsonProcessingException e) {
			log.error("제보 메시지 파싱 실패: {}", message, e);
			throw new AdminException(AdminErrorCode.REPORT_PAYLOAD_INVALID);
		}
	}

	// StoreReport.message 가 JSON 직렬화된 MenuEditReportRequest 구조와 동일
	// 기존 DTO를 그대로 역직렬화하려면 menu 패키지 의존이 admin → menu 방향으로 생기는데 이미 다른 곳에서 사용 중이라 OK
	// 단, MenuEditReportRequest는 @Positive 등 validation annotation을 들고 있어 별도 page-record 정의가 더 깔끔
	private record MenuEditPayload(
			Integer price,
			List<Long> deleteImageIds,
			List<ReportImageRequest> addImages,
			String reason) {
	}
}
