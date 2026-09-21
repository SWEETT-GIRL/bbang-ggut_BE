package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.repository.MemberRestrictionRepository;

/**
 * 게스트는 저장·기록·만들기를 못 한다. 탐색·검색과 로그인·알림 설정 같은 계정 관리는 된다.
 */
@ExtendWith(MockitoExtension.class)
class GuestWriteGuardTest {

	private static final Long MEMBER_ID = 1L;

	@Mock
	private MemberRepository memberRepository;

	@InjectMocks
	private MemberReader memberReader;

	private Member guest() {
		Member member = Member.builder().nickname("게스트").build();
		member.markAsGuest();
		return member;
	}

	private Member loggedIn() {
		return Member.builder().nickname("빵순이").build();
	}

	@Nested
	@DisplayName("로그인 요구")
	class RequireLoggedIn {

		@Test
		@DisplayName("게스트면 막는다")
		void blocksGuest() {
			given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(guest()));

			assertThatThrownBy(() -> memberReader.requireLoggedIn(MEMBER_ID))
				.isInstanceOf(MemberException.class)
				.hasMessageContaining("로그인하면 지금까지 쌓은 것이 그대로 따라옵니다");
		}

		@Test
		@DisplayName("로그인한 회원은 통과한다")
		void allowsLoggedInMember() {
			given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(loggedIn()));

			assertThatCode(() -> memberReader.requireLoggedIn(MEMBER_ID)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("401 이 아니라 403 이다 — 401 이면 앱이 게스트 세션을 재발급해 같은 요청을 되풀이한다")
		void answersWithForbiddenNotUnauthorized() {
			assertThat(MemberErrorCode.GUEST_LOGIN_REQUIRED.getHttpStatus())
				.isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	@Nested
	@DisplayName("쓰기 자격 검사")
	class ValidateCanWrite {

		@Mock
		private MemberRestrictionRepository memberRestrictionRepository;

		@Test
		@DisplayName("게스트는 제재가 없어도 막힌다")
		void blocksGuestEvenWithoutRestriction() {
			MemberRestrictionReader reader = new MemberRestrictionReader(memberRestrictionRepository, memberReader);
			given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(guest()));

			assertThatThrownBy(() -> reader.validateCanWrite(MEMBER_ID))
				.isInstanceOf(MemberException.class)
				.hasMessageContaining("로그인하면");
		}

		@Test
		@DisplayName("로그인했고 제재도 없으면 통과한다")
		void allowsCleanMember() {
			MemberRestrictionReader reader = new MemberRestrictionReader(memberRestrictionRepository, memberReader);
			given(memberRepository.findById(MEMBER_ID)).willReturn(Optional.of(loggedIn()));
			given(memberRestrictionRepository.findActiveByMemberIdAndRestrictionTypes(
				any(), any(), any(LocalDateTime.class))).willReturn(List.of());

			assertThatCode(() -> reader.validateCanWrite(MEMBER_ID)).doesNotThrowAnyException();
		}
	}
}
