package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;

@DisplayName("CourseStoreMemo")
class CourseStoreMemoTest {

	private CourseStoreMemo newMemo() {
		CourseStore courseStore = mock(CourseStore.class);
		given(courseStore.getId()).willReturn(501L);
		Member member = mock(Member.class);
		given(member.getId()).willReturn(1L);
		return CourseStoreMemo.of(courseStore, member, "소금빵 꼭 먹기", 0L);
	}

	@Test
	@DisplayName("of: 정류장·회원 id 로 복합키를 만들고 seq 를 든다")
	void of_setsId() {
		CourseStoreMemo memo = newMemo();

		assertThat(memo.getId().getCourseStoreId()).isEqualTo(501L);
		assertThat(memo.getId().getMemberId()).isEqualTo(1L);
		assertThat(memo.getContent()).isEqualTo("소금빵 꼭 먹기");
		assertThat(memo.getSeq()).isZero();
		assertThat(memo.isDeleted()).isFalse();
	}

	@Nested
	@DisplayName("applyIfNewer")
	class ApplyIfNewer {

		private CourseStoreMemo memoWithSeq(long seq) {
			CourseStore courseStore = mock(CourseStore.class);
			given(courseStore.getId()).willReturn(501L);
			Member member = mock(Member.class);
			given(member.getId()).willReturn(1L);
			return CourseStoreMemo.of(courseStore, member, "소금빵 꼭 먹기", seq);
		}

		@Test
		@DisplayName("더 큰 seq 의 글은 내용과 seq 를 바꾸고 true")
		void newerSeq_applies() {
			CourseStoreMemo memo = memoWithSeq(100L);

			boolean applied = memo.applyIfNewer("주차 어렵대", 101L);

			assertThat(applied).isTrue();
			assertThat(memo.getContent()).isEqualTo("주차 어렵대");
			assertThat(memo.getSeq()).isEqualTo(101L);
		}

		@Test
		@DisplayName("같거나 작은 seq 의 글은 무시하고 false — 내용·seq 그대로")
		void olderOrSameSeq_ignored() {
			CourseStoreMemo memo = memoWithSeq(100L);

			assertThat(memo.applyIfNewer("늦게 온 글", 100L)).isFalse();
			assertThat(memo.applyIfNewer("더 늦게 온 글", 99L)).isFalse();
			assertThat(memo.getContent()).isEqualTo("소금빵 꼭 먹기");
			assertThat(memo.getSeq()).isEqualTo(100L);
		}

		@Test
		@DisplayName("빈 내용의 큰 seq 는 지우기 — deletedAt 이 찍히고 seq 는 올라간다")
		void blankContent_deletes() {
			CourseStoreMemo memo = memoWithSeq(100L);

			assertThat(memo.applyIfNewer("", 101L)).isTrue();

			assertThat(memo.isDeleted()).isTrue();
			assertThat(memo.getSeq()).isEqualTo(101L);
			assertThat(memo.getContent()).isEqualTo("소금빵 꼭 먹기");
		}

		@Test
		@DisplayName("지워진 행에 더 큰 seq 의 글이 오면 되살아난다")
		void deletedRow_newerContent_restores() {
			CourseStoreMemo memo = memoWithSeq(100L);
			memo.applyIfNewer("", 101L);

			assertThat(memo.applyIfNewer("다시 쓰는 메모", 102L)).isTrue();

			assertThat(memo.isDeleted()).isFalse();
			assertThat(memo.getContent()).isEqualTo("다시 쓰는 메모");
			assertThat(memo.getSeq()).isEqualTo(102L);
		}

		@Test
		@DisplayName("지워진 행에 더 작은 seq 의 옛 글은 되살리지 못한다")
		void deletedRow_olderContent_staysDeleted() {
			CourseStoreMemo memo = memoWithSeq(100L);
			memo.applyIfNewer("", 105L);

			assertThat(memo.applyIfNewer("지우기 전에 보낸 글", 103L)).isFalse();

			assertThat(memo.isDeleted()).isTrue();
			assertThat(memo.getSeq()).isEqualTo(105L);
		}

		@Test
		@DisplayName("seq 를 모르던 옛 행(0)은 어떤 새 seq 도 받는다")
		void legacyRow_acceptsAnySeq() {
			CourseStoreMemo memo = newMemo();
			assertThat(memo.getSeq()).isZero();

			assertThat(memo.applyIfNewer("첫 초안", 1L)).isTrue();
			assertThat(memo.getSeq()).isEqualTo(1L);
		}
	}
}
