package sweetgirl.croissant.course.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import sweetgirl.croissant.member.entity.Member;

@DisplayName("CourseMember")
class CourseMemberTest {

	private CourseMember newMember() {
		Course course = mock(Course.class);
		given(course.getId()).willReturn(10L);
		Member member = mock(Member.class);
		given(member.getId()).willReturn(1L);
		return CourseMember.of(course, member);
	}

	@Test
	@DisplayName("of: 코스·회원 id로 복합키를 만들고 참여 시각을 지금으로 잡는다")
	void of_setsIdAndJoinedAt() {
		LocalDateTime before = LocalDateTime.now();

		CourseMember courseMember = newMember();

		assertThat(courseMember.getId().getCourseId()).isEqualTo(10L);
		assertThat(courseMember.getId().getMemberId()).isEqualTo(1L);
		assertThat(courseMember.getJoinedAt()).isAfterOrEqualTo(before);
		assertThat(courseMember.isDeleted()).isFalse();
	}

	@Test
	@DisplayName("leave 뒤 rejoin 하면 같은 행이 되살아나고 참여 시각이 새로 잡힌다")
	void rejoin_revivesSameRow() {
		CourseMember courseMember = newMember();
		LocalDateTime firstJoinedAt = courseMember.getJoinedAt();
		courseMember.leave();
		assertThat(courseMember.isDeleted()).isTrue();

		courseMember.rejoin();

		assertThat(courseMember.isDeleted()).isFalse();
		assertThat(courseMember.getJoinedAt()).isAfterOrEqualTo(firstJoinedAt);
	}
}
