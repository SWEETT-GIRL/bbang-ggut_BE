package sweetgirl.croissant.menu.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.menu.dto.classification.MenuKindVerdict;
import sweetgirl.croissant.menu.entity.Menu;
import sweetgirl.croissant.menu.entity.MenuCategory;
import sweetgirl.croissant.menu.entity.MenuNameClassification;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.repository.MenuCategoryRepository;
import sweetgirl.croissant.menu.repository.MenuNameClassificationRepository;
import sweetgirl.croissant.menu.repository.MenuRepository;

/** AI 판정을 DB 에 쓰는 쪽 — 짧은 트랜잭션 하나. Gemini 호출은 이 안에 없다 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MenuClassificationCommandService {

	private final MenuRepository menuRepository;
	private final MenuCategoryRepository menuCategoryRepository;
	private final MenuNameClassificationRepository menuNameClassificationRepository;

	/**
	 * @param menuIds       이번 배치의 메뉴
	 * @param verdictByKey  정규화 키 → 판정 (캐시 hit + 새 판정 전부)
	 * @param freshVerdicts 이번에 AI 가 새로 낸 판정 — 캐시에 남긴다. 갈린 것도 남겨 같은 이름을 다시 안 묻게
	 */
	public void apply(
			List<Long> menuIds,
			Map<String, MenuKindVerdict> verdictByKey,
			List<MenuKindVerdict> freshVerdicts,
			String model) {
		menuNameClassificationRepository.saveAll(freshVerdicts.stream()
			.map(verdict -> MenuNameClassification.from(verdict, model))
			.toList());

		Map<MenuKind, MenuCategory> categoryByKind = menuCategoryRepository.findAll().stream()
			.collect(Collectors.toMap(MenuCategory::getName, Function.identity()));

		for (Menu menu : menuRepository.findAllById(menuIds)) {
			MenuKindVerdict verdict = verdictByKey.get(menu.getNormalizedName());
			if (verdict == null || !verdict.decided()) {
				menu.markClassificationAttempted();
				continue;
			}
			MenuCategory category = categoryByKind.get(verdict.kind());
			if (category == null) {
				// enum 엔 있는데 menu_category 행이 없다 — 시딩 마이그레이션 누락. 미분류로 남겨 검수에서 드러나게
				log.warn("menu_category 에 {} 행이 없어 메뉴 {} 를 분류하지 못했다", verdict.kind(), menu.getId());
				menu.markClassificationAttempted();
				continue;
			}
			menu.applyClassification(category, verdict.bakeryItem());
		}
	}
}
