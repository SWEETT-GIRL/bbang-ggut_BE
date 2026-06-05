package sweetgirl.croissant.menu.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.PresignedUrlResponse;
import sweetgirl.croissant.external.aws.util.S3PathGenerator;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.dto.request.MenuAddReportRequest;
import sweetgirl.croissant.menu.entity.MenuAddReport;
import sweetgirl.croissant.menu.entity.MenuAddReportImageFile;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuAddReportImageFileRepository;
import sweetgirl.croissant.menu.repository.MenuAddReportRepository;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.store.code.StoreErrorCode;
import sweetgirl.croissant.store.entity.Store;
import sweetgirl.croissant.store.exception.StoreException;
import sweetgirl.croissant.store.repository.StoreRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuAddReportService {

	private final MenuAddReportRepository menuAddReportRepository;
	private final MenuAddReportImageFileRepository menuAddReportImageFileRepository;
	private final StoreRepository storeRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MemberRepository memberRepository;
	private final S3Service s3Service;

	public PresignedUrlResponse getPresignedUrl(String mimeType) {
		String path = S3PathGenerator.getMenuAddReportTempPath();
		return s3Service.generatePresignedUrl(path, mimeType);
	}

	@Transactional
	public void createMenuAddReport(Long storeId, Long memberId, MenuAddReportRequest request) {
		Store store = storeRepository.findById(storeId)
			.orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));

		MenuCategory menuCategory = menuCategoryRepository.findById(request.getMenuCategoryId())
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND));

		Member member = (memberId != null) ? memberRepository.getReferenceById(memberId) : null;

		MenuAddReport menuAddReport = MenuAddReport.builder()
			.store(store)
			.member(member)
			.menuCategory(menuCategory)
			.name(request.getName())
			.price(request.getPrice())
			.description(request.getDescription())
			.build();

		menuAddReportRepository.save(menuAddReport);

		List<MenuAddReportImageFile> imageFiles = request.getImages().stream()
			.map(img -> MenuAddReportImageFile.builder()
				.menuAddReport(menuAddReport)
				.name(img.getFileName())
				.mimeType(MimeType.fromString(img.getMimeType()))
				.awsKey(img.getAwsKey())
				.build())
			.toList();
		menuAddReportImageFileRepository.saveAll(imageFiles);
	}
}
