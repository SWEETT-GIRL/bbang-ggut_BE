package sweetgirl.croissant.menu.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.menu.config.MenuClassificationProperties;
import sweetgirl.croissant.menu.dto.classification.MenuClassificationBatchResult;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;

/**
 * 배치 한 번: 미분류 메뉴 → 캐시 → (없는 이름만) AI → 적용.
 *
 * <p>일부러 트랜잭션을 걸지 않는다. 조회와 적용은 각자 짧은 트랜잭션이고, 그 사이 Gemini 호출(수십 초)
 * 동안 DB 커넥션을 잡고 있지 않기 위해서다.
 */
@Service
@RequiredArgsConstructor
public class MenuClassificationService {

	private final MenuRepository menuRepository;
	private final MenuNameClassificationRepository menuNameClassificationRepository;
	private final MenuKindClassifier menuKindClassifier;
	private final MenuClassificationCommandService menuClassificationCommandService;
	private final MenuClassificationProperties properties;

	public MenuClassificationBatchResult classifyNextBatch() {
		List<Menu> menus = menuRepository.findAwaitingClassification(PageRequest.of(0, properties.getBatchSize()));
		if (menus.isEmpty()) {
			return MenuClassificationBatchResult.empty();
		}

		// 같은 정규화 키는 한 번만 묻는다. 프롬프트엔 처음 본 원본 이름을 넣는다
		Map<String, String> nameByKey = new LinkedHashMap<>();
		menus.forEach(menu -> nameByKey.putIfAbsent(menu.getNormalizedName(), menu.getName()));

		Map<String, MenuKindVerdict> verdictByKey = new HashMap<>();
		menuNameClassificationRepository.findAllById(nameByKey.keySet())
			.forEach(cached -> verdictByKey.put(cached.getNormalizedName(), cached.toVerdict()));
		int cacheHits = verdictByKey.size();

		Map<String, String> misses = new LinkedHashMap<>(nameByKey);
		misses.keySet().removeAll(verdictByKey.keySet());
		List<MenuKindVerdict> fresh = menuKindClassifier.classify(misses);
		fresh.forEach(verdict -> verdictByKey.put(verdict.normalizedName(), verdict));

		menuClassificationCommandService.apply(
			menus.stream().map(Menu::getId).toList(), verdictByKey, fresh, menuKindClassifier.modelName());

		return new MenuClassificationBatchResult(
			menus.size(),
			cacheHits,
			fresh.size(),
			(int)fresh.stream().filter(verdict -> verdict.decided() && verdict.bakeryItem()).count(),
			(int)fresh.stream().filter(verdict -> !verdict.bakeryItem()).count(),
			(int)fresh.stream().filter(verdict -> !verdict.decided()).count());
	}
}
