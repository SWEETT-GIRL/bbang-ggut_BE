package sweetgirl.croissant.breadranking.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;
import sweetgirl.croissant.breadranking.dto.projection.BreadCategoryVoters;
import sweetgirl.croissant.breadranking.entity.BreadCategory;
import sweetgirl.croissant.breadranking.entity.MemberBreadCategory;
import sweetgirl.croissant.breadranking.enums.BreadGroup;
import sweetgirl.croissant.config.persistence.JpaConfig;
import sweetgirl.croissant.menu.enums.MenuKind;

/** 공용 목록의 노출 규칙과 순서(#843) — 기본·내 것·3명 이상, 고정 자리 → 기본 → id. 1등 표 수 정렬은 가게가 필요해 H2 밖(랩). */
@DataJpaTest
@Import(JpaConfig.class)
class BreadCategoryRepositoryTest {

	private static final long MIN_TAB_MEMBERS = 3;
	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private BreadCategoryRepository repository;

	@Autowired
	private EntityManager entityManager;

	private BreadCategory salt;
	private BreadCategory donut;
	private BreadCategory croissant;
	private BreadCategory mine;
	private BreadCategory popularCustom;
	private BreadCategory lonelyCustom;

	@BeforeEach
	void seed() {
		salt = repository.save(BreadCategory.defaultOf(MenuKind.소금빵, BreadGroup.빵));
		croissant = repository.save(BreadCategory.defaultOf(MenuKind.크루아상, BreadGroup.크루아상_페이스트리));
		donut = repository.save(BreadCategory.defaultOf(MenuKind.도넛, BreadGroup.베이글_도넛));
		ReflectionTestUtils.setField(salt, "displayOrder", 1);
		ReflectionTestUtils.setField(donut, "displayOrder", 8);
		mine = repository.save(BreadCategory.custom("검증용빵", BreadGroup.빵, ME));
		popularCustom = repository.save(BreadCategory.custom("트러플 소금빵", BreadGroup.빵, OTHER));
		lonelyCustom = repository.save(BreadCategory.custom("ㅔㄷㅊㅁㅜ ㅔㅑㄷ", BreadGroup.빵, OTHER));
		for (long memberId = 10; memberId < 10 + MIN_TAB_MEMBERS; memberId++) {
			entityManager.persist(MemberBreadCategory.append(memberId, popularCustom, 0));
		}
		entityManager.persist(MemberBreadCategory.append(OTHER, lonelyCustom, 0));
		entityManager.flush();
		entityManager.clear();
	}

	private List<Long> ids(Long memberId) {
		return repository.findVisibleOrderByDisplay(memberId, MIN_TAB_MEMBERS).stream()
			.map(BreadCategoryVoters::category).map(BreadCategory::getId).toList();
	}

	@Test
	@DisplayName("게스트는 기본 종류와 3명 이상이 탭에 넣은 것만 본다 — 한 사람만 쓰는 이름은 안 보인다")
	void guestSeesDefaultsAndSharedOnly() {
		assertThat(ids(null)).containsExactly(salt.getId(), donut.getId(), croissant.getId(), popularCustom.getId());
	}

	@Test
	@DisplayName("로그인 회원은 내가 만든 것도 본다 — 아무도 탭에 안 넣었어도")
	void memberAlsoSeesOwnCustom() {
		assertThat(ids(ME)).containsExactly(salt.getId(), donut.getId(), croissant.getId(), mine.getId(),
			popularCustom.getId());
	}

	@Test
	@DisplayName("남이 만든 외톨이 이름은 만든 사람 본인에게만 보인다")
	void lonelyCustomVisibleOnlyToCreator() {
		assertThat(ids(ME)).doesNotContain(lonelyCustom.getId());
		assertThat(ids(OTHER)).contains(lonelyCustom.getId());
	}

	@Test
	@DisplayName("고정 자리가 있는 것이 그 순서로 맨 앞 — 도넛(8)이 자리 없는 크루아상보다 앞, 소금빵(1)이 도넛보다 앞")
	void pinnedFirstThenDefaultsById() {
		List<Long> ids = ids(null);
		assertThat(ids.indexOf(salt.getId())).isLessThan(ids.indexOf(donut.getId()));
		assertThat(ids.indexOf(donut.getId())).isLessThan(ids.indexOf(croissant.getId()));
		assertThat(ids.indexOf(croissant.getId())).isLessThan(ids.indexOf(popularCustom.getId()));
	}
}
