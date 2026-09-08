package sweetgirl.croissant.admin.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.request.AdminMenuClassificationConfirmRequest;
import sweetgirl.croissant.admin.dto.response.AdminMenuClassificationConfirmResponse;
import sweetgirl.croissant.menu.code.MenuErrorCode;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.exception.MenuException;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;

/**
 * 검수 큐 처리 — AI 가 3회 물어도 갈린 이름에 사람이 kind 를 정한다.
 *
 * <p>이름(정규화 키) 단위다. 캐시에 ADMIN 판정을 남기니 같은 이름이 다시 들어와도 AI 를 안 부르고 그 kind 를 받고,
 * 지금 있는 미분류 활성 메뉴엔 바로 적용한다. 이미 분류된 메뉴는 건드리지 않는다 — 크롤러 룰이나 다른 사람이
 * 정한 값을 이름 하나로 덮어쓰지 않기 위해.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminMenuClassificationCommandService {

	private final MenuNameClassificationRepository menuNameClassificationRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MenuRepository menuRepository;

	public AdminMenuClassificationConfirmResponse confirm(AdminMenuClassificationConfirmRequest request) {
		String normalizedName = MenuNameNormalizer.normalize(request.name());
		MenuKind kind = parseKind(request.kind());
		boolean bakeryItem = request.bakeryItemOrDefault();
		MenuCategory category = menuCategoryRepository.findByName(kind)
			.orElseThrow(() -> new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND));

		menuNameClassificationRepository.findById(normalizedName)
			.ifPresentOrElse(
				cached -> cached.confirmByAdmin(kind, bakeryItem),
				() -> menuNameClassificationRepository.save(
					MenuNameClassification.confirmedByAdmin(normalizedName, kind, bakeryItem)));

		List<Menu> unclassified = menuRepository
			.findByNormalizedNameAndMenuCategoryIsNullAndDeletedAtIsNull(normalizedName);
		unclassified.forEach(menu -> menu.applyClassification(category, bakeryItem));

		return new AdminMenuClassificationConfirmResponse(normalizedName, kind.name(), bakeryItem, unclassified.size());
	}

	private MenuKind parseKind(String kindName) {
		try {
			return MenuKind.valueOf(kindName.strip());
		} catch (IllegalArgumentException notAKind) {
			throw new MenuException(MenuErrorCode.MENU_CATEGORY_NOT_FOUND);
		}
	}
}
