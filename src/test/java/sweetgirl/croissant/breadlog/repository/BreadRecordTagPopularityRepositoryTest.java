package sweetgirl.croissant.breadlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadlog.dto.projection.BreadCategoryCountProjection;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.service.TutorialRecordTemplate;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.member.entity.Member;

/** created_at 은 JPA 감사(auditing)가 채운다 — @DataJpaTest 는 그 설정을 안 올리므로 들여온다. */
@DataJpaTest
@Import(JpaConfig.class)
class BreadRecordTagPopularityRepositoryTest {

	@Autowired
	BreadRecordTagRepository tagRepository;
	@Autowired
	EntityManager entityManager;
	@Autowired
	JdbcTemplate jdbc;

	private BreadRecord record(Member member) {
		BreadRecord record = BreadRecord.builder()
			.member(member).storeName("성심당").eatenAt(LocalDateTime.now()).build();
		entityManager.persist(record);
		return record;
	}

	private BreadRecordTag tag(BreadRecord record, String name, String breadCategory) {
		BreadRecordTag tag = BreadRecordTag.builder().record(record).name(name).breadCategory(breadCategory).build();
		entityManager.persist(tag);
		return tag;
	}

	@Test
	@DisplayName("기간 안의 태그를 분류별로 많이 붙은 순으로 센다 — '빵' · '기타' · 튜토리얼 · 지운 기록 · 기간 밖은 뺀다")
	void countsPopularCategories() {
		Member member = Member.registering("빵순이", LocalDateTime.now());
		entityManager.persist(member);
		BreadRecord record = record(member);
		tag(record, "소금빵", "소금빵");
		tag(record, "명란바게트", "바게트");
		tag(record, "소금빵2", "소금빵");
		tag(record, "모르는빵", "빵");
		tag(record, "뭔가", "기타");
		BreadRecordTag old = tag(record(member), "크루아상", "크루아상");
		BreadRecord tutorial = record(member);
		tutorial.markTutorial(TutorialRecordTemplate.CODE);
		tag(tutorial, "단팥빵", "단팥빵");
		BreadRecord deleted = record(member);
		tag(deleted, "식빵", "식빵");
		deleted.softDelete();
		entityManager.flush();
		jdbc.update("UPDATE bread_record_tag SET created_at = ? WHERE id = ?", LocalDateTime.now().minusDays(40),
			old.getId());
		entityManager.clear();

		List<BreadCategoryCountProjection> rows = tagRepository.findPopularCategories(
			LocalDateTime.now().minusDays(30), BreadRecordTag.fallbackCategories(), PageRequest.of(0, 10));

		assertThat(rows).extracting(BreadCategoryCountProjection::getName).containsExactly("소금빵", "바게트");
		assertThat(rows).extracting(BreadCategoryCountProjection::getTagCount).containsExactly(2L, 1L);
	}

	@Test
	@DisplayName("size 만큼만 돌려준다")
	void limitsBySize() {
		Member member = Member.registering("빵순이", LocalDateTime.now());
		entityManager.persist(member);
		BreadRecord record = record(member);
		tag(record, "소금빵", "소금빵");
		tag(record, "바게트", "바게트");
		tag(record, "식빵", "식빵");
		entityManager.flush();

		assertThat(tagRepository.findPopularCategories(
			LocalDateTime.now().minusDays(30), BreadRecordTag.fallbackCategories(), PageRequest.of(0, 2))).hasSize(2);
	}
}
