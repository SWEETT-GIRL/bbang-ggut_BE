package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("SocialMemberReader")
class SocialMemberReaderTest {

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private SocialMemberReader socialMemberReader;

	private Member member(String memberType) {
		Member member = Member.builder().nickname("빵순이").build();
		ReflectionTestUtils.setField(member, "id", 7L);
		ReflectionTestUtils.setField(member, "memberType", memberType);
		return member;
	}

	@Test
	@DisplayName("일반 회원은 상대가 된다")
	void user() {
		given(memberRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(member("USER")));

		assertThat(socialMemberReader.findSocial(7L)).isPresent();
	}

	@Test
	@DisplayName("게스트 · 블로그 봇은 상대가 아니다")
	void guestAndBot() {
		given(memberRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(member("GUEST")));
		assertThat(socialMemberReader.findSocial(7L)).isEmpty();

		given(memberRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.of(member("BLOG_BOT")));
		assertThat(socialMemberReader.findSocial(7L)).isEmpty();
	}

	@Test
	@DisplayName("탈퇴했거나 없으면 비어 있다. id 가 null 이어도 조회하지 않는다")
	void deletedOrMissing() {
		Member deleted = member("USER");
		deleted.softDelete();
		assertThat(socialMemberReader.isSocial(deleted)).isFalse();

		given(memberRepository.findByIdAndDeletedAtIsNull(7L)).willReturn(Optional.empty());
		assertThat(socialMemberReader.findSocial(7L)).isEmpty();
		assertThat(socialMemberReader.findSocial(null)).isEmpty();
	}
}
