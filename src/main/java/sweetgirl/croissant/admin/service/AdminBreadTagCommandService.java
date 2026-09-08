package sweetgirl.croissant.admin.service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.admin.dto.response.AdminBreadTagReclassifyResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.breadlog.service.BreadCategoryClassifier;

/**
 * 빵로그 태그 전체를 현재 분류 규칙으로 다시 분류한다.
 * 분류기가 contains 에서 끝단어 방식으로 바뀌면서(2026-09-08) 그전에 저장된 태그는 옛 규칙 결과라 —
 * 태그 수가 천 건 안팎이라 한 트랜잭션에 전부 싣는다. 이름은 그대로, breadCategory 만 바뀐다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminBreadTagCommandService {

	private final BreadRecordTagRepository breadRecordTagRepository;
	private final BreadCategoryClassifier breadCategoryClassifier;

	public AdminBreadTagReclassifyResponse reclassifyAll() {
		List<BreadRecordTag> tags = breadRecordTagRepository.findAll();
		Map<String, Long> moves = new TreeMap<>();
		long changed = 0;
		for (BreadRecordTag tag : tags) {
			String before = tag.getBreadCategory();
			String after = breadCategoryClassifier.classify(tag.getName());
			if (tag.reclassify(after)) {
				changed++;
				moves.merge(before + " → " + after, 1L, Long::sum);
			}
		}
		return new AdminBreadTagReclassifyResponse(tags.size(), changed, moves);
	}
}
