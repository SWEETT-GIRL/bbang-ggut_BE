package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;

@DisplayName("CourseStoreMemo")
class CourseStoreMemoTest {

	private CourseStoreMemo newMemo() {
		CourseStore courseStore = mock(CourseStore.class);
		given(courseStore.getId()).willReturn(501L);
		Member member = mock(Member.class);
		given(member.getId()).willReturn(1L);
		return CourseStoreMemo.of(courseStore, member, "소금빵 꼭 먹기");
	}

	@Test
	@DisplayName("of: 정류장·회원 id 로 복합키를 만든다")
	void of_setsId() {
		CourseStoreMemo memo = newMemo();

		assertThat(memo.getId().getCourseStoreId()).isEqualTo(501L);
		assertThat(memo.getId().getMemberId()).isEqualTo(1L);
		assertThat(memo.getContent()).isEqualTo("소금빵 꼭 먹기");
		assertThat(memo.isDeleted()).isFalse();
	}

	@Test
	@DisplayName("softDelete 뒤 restore 하면 같은 행이 새 내용으로 되살아난다")
	void restore_revivesSameRow() {
		CourseStoreMemo memo = newMemo();
		memo.softDelete();
		assertThat(memo.isDeleted()).isTrue();

		memo.restore("주차 어렵대");

		assertThat(memo.isDeleted()).isFalse();
		assertThat(memo.getContent()).isEqualTo("주차 어렵대");
	}
}
