package sweetgirl.croissant.follow.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;

@ExtendWith(MockitoExtension.class)
@DisplayName("FollowVisibilityReader")
class FollowVisibilityReaderTest {

	private static final Long VIEWER = 1L;
	private static final Long OWNER = 2L;

	@Mock
	private MemberFollowRepository memberFollowRepository;

	@InjectMocks
	private FollowVisibilityReader reader;

	private Member owner(boolean isPrivate) {
		Member member = Member.builder().nickname("주인").build();
		ReflectionTestUtils.setField(member, "id", OWNER);
		member.changePrivacy(isPrivate);
		return member;
	}

	@Test
	@DisplayName("공개 계정은 누구나(비로그인도) 본다 — 팔로우를 찾아보지 않는다")
	void publicAccount() {
		assertThat(reader.canViewRecords(null, owner(false))).isTrue();
		assertThat(reader.canViewRecords(VIEWER, owner(false))).isTrue();
		then(memberFollowRepository).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("비공개 계정은 본인과 팔로워만 본다")
	void privateAccount() {
		Member owner = owner(true);
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(VIEWER, OWNER)).willReturn(true);

		assertThat(reader.canViewRecords(OWNER, owner)).isTrue();
		assertThat(reader.canViewRecords(VIEWER, owner)).isTrue();
	}

	@Test
	@DisplayName("비공개 계정을 팔로워가 아닌 사람 · 비로그인은 못 본다")
	void privateAccountHidden() {
		Member owner = owner(true);
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(VIEWER, OWNER)).willReturn(false);

		assertThat(reader.canViewRecords(VIEWER, owner)).isFalse();
		assertThat(reader.canViewRecords(null, owner)).isFalse();
	}
}
