package sweetgirl.croissant.breadranking.service;

import java.util.Arrays;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.breadlog.service.BreadCategoryClassifier;
import sweetgirl.croissant.breadranking.code.BreadRankingErrorCode;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryCreateResult;
import sweetgirl.croissant.breadranking.dto.response.BreadCategoryResponse;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.breadranking.exception.BreadRankingException;
import sweetgirl.croissant.breadranking.repository.BreadCategoryRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.menu.enums.MenuKind;
import sweetgirl.croissant.menu.util.MenuNameNormalizer;
import sweetgirl.croissant.review.service.ObjectionableContentFilter;

/**
 * 빵 종류 직접 만들기 — 공용 풀이라 모두의 목록에 뜬다. 같은 이름(정규화 키)이 있으면 새로 만들지 않고 그걸 돌려준다.
 * 대분류는 빵로그 태그와 같은 분류기로 세분류를 맞춰 정한다("트러플 소금빵" → 소금빵 → 빵). 못 맞추면 없음(기본 칩 순서).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class BreadCategoryCommandService {

	private final MemberReader memberReader;
	private final BreadCategoryRepository categoryRepository;
	private final BreadCategoryClassifier categoryClassifier;
	private final ObjectionableContentFilter contentFilter;

	public BreadCategoryCreateResult create(Long memberId, String rawName) {
		memberReader.requireLoggedIn(memberId);
		String name = rawName == null ? "" : rawName.strip();
		String nameKey = MenuNameNormalizer.normalize(name);
		if (name.length() < BreadCategory.NAME_MIN_LENGTH || name.length() > BreadCategory.NAME_MAX_LENGTH
			|| nameKey.isEmpty()) {
			throw new BreadRankingException(BreadRankingErrorCode.BREAD_CATEGORY_INVALID_NAME);
		}
		if (contentFilter.containsObjectionableContent(name)) {
			throw new BreadRankingException(BreadRankingErrorCode.BREAD_CATEGORY_NAME_REJECTED);
		}
		Optional<BreadCategory> existing = categoryRepository.findByNameKey(nameKey);
		if (existing.isPresent()) {
			return new BreadCategoryCreateResult(BreadCategoryResponse.of(existing.get(), 0), false);
		}
		BreadCategory created = categoryRepository.save(
			BreadCategory.custom(name, resolveGroup(name).orElse(null), memberId));
		return new BreadCategoryCreateResult(BreadCategoryResponse.of(created, 0), true);
	}

	private Optional<BreadGroup> resolveGroup(String name) {
		String kindName = categoryClassifier.classify(name);
		return Arrays.stream(MenuKind.values())
			.filter(kind -> kind.name().equals(kindName))
			.findFirst()
			.flatMap(BreadGroup::of);
	}
}
