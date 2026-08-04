package sweetgirl.croissant.menu.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.menu.dto.response.StoreMenuResponse;
import sweetgirl.croissant.store.entity.Store;

class MenuTest {

	@Test
	@DisplayName("회원이 추가한 미분류 메뉴는 이름을 정규화하고 작성자를 남긴다")
	void createMemberMenu_withoutCategory() {
		Member member = Member.builder().nickname("테스터").build();
		Menu menu = Menu.builder()
			.store(Store.builder().name("빵집").address("서울 성동구").build())
			.name(" Black  Sesame ")
			.createdByMember(member)
			.build();

		assertThat(menu.getMenuCategory()).isNull();
		assertThat(menu.getNormalizedName()).isEqualTo("blacksesame");
		assertThat(menu.getCreatedByMember()).isSameAs(member);
		assertThat(StoreMenuResponse.of(menu, null, false, 0).category()).isNull();
	}

	@Test
	@DisplayName("메뉴 이름을 수정하면 중복 확인용 이름도 함께 갱신한다")
	void updateName_updatesNormalizedName() {
		Menu menu = Menu.builder()
			.store(Store.builder().name("빵집").address("서울 성동구").build())
			.name("소금빵")
			.build();

		menu.update("흑임자 소금빵", null, null, null);

		assertThat(menu.getNormalizedName()).isEqualTo("흑임자소금빵");
	}
}
