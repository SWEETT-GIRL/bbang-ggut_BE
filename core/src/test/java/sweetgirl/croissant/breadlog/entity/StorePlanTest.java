package sweetgirl.croissant.breadlog.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.store.entity.Store;

class StorePlanTest {

	@Test
	@DisplayName("회원과 가게 ID를 복합 키로 계획 메모를 생성한다")
	void create_usesMemberAndStoreAsId() {
		Member member = Member.builder().nickname("테스터").build();
		Store store = Store.builder().name("밀도 성수").address("서울 성동구").build();
		ReflectionTestUtils.setField(member, "id", 7L);
		ReflectionTestUtils.setField(store, "id", 10L);

		StorePlan plan = StorePlan.builder()
			.member(member)
			.store(store)
			.memo("소금빵 꼭 먹기")
			.build();

		assertThat(plan.getId()).isEqualTo(new StorePlanId(7L, 10L));
		assertThat(plan.getMemo()).isEqualTo("소금빵 꼭 먹기");
	}
}
