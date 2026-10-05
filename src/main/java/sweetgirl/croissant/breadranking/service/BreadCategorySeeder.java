package sweetgirl.croissant.breadranking.service;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.menu.enums.MenuCategory;
import sweetgirl.croissant.menu.enums.MenuKind;

/**
 * 기본 빵 종류 = {@link MenuKind}(빵·기타 제외). 마이그레이션이 처음 89종을 넣었지만, 그 뒤 enum 에 종류가 늘면 사람이 시딩을
 * 기억해야 했다 — 기동 때 빠진 것만 채워 그 의존을 없앤다. 있는 행은 건드리지 않는다(이름·대분류를 바꾸는 건 마이그레이션 몫).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BreadCategorySeeder implements ApplicationRunner {

	private final BreadCategoryRepository categoryRepository;

	@Override
	@Transactional
	public void run(ApplicationArguments arguments) {
		Set<MenuKind> existing = EnumSet.noneOf(MenuKind.class);
		existing.addAll(categoryRepository.findAllMenuKinds());
		int added = 0;
		for (MenuKind kind : MenuKind.values()) {
			if (MenuCategory.UNCATEGORIZED_KINDS.contains(kind) || existing.contains(kind)) {
				continue;
			}
			categoryRepository.save(BreadCategory.defaultOf(kind, BreadGroup.of(kind).orElse(null)));
			added++;
		}
		if (added > 0) {
			log.info("[빵 종류] 기본 목록에 빠진 MenuKind {}종을 채웠다", added);
		}
	}
}
