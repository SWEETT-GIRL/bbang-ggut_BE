package sweetgirl.croissant.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.code.MemberErrorCode;
import sweetgirl.croissant.member.code.NicknameErrorCode;
import sweetgirl.croissant.member.dto.response.MemberProfileResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.entity.NicknameHistory;
import sweetgirl.croissant.member.exception.MemberException;
import sweetgirl.croissant.member.exception.NicknameException;
import sweetgirl.croissant.member.repository.NicknameHistoryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("NicknameCommandService")
class NicknameCommandServiceTest {

	private static final Long MEMBER_ID = 1L;

	@Mock
	private MemberReader memberReader;
	@Mock
	private MemberRestrictionReader memberRestrictionReader;
	@Mock
	private NicknamePolicy nicknamePolicy;
	@Mock
	private NicknameHistoryRepository nicknameHistoryRepository;

	@InjectMocks
	private NicknameCommandService nicknameCommandService;

	private Member member;

	@BeforeEach
	void setUp() {
		member = Member.builder().nickname("여린 바타르").build();
		ReflectionTestUtils.setField(member, "id", MEMBER_ID);
		lenient().when(memberReader.getMember(MEMBER_ID)).thenReturn(member);
	}

	@Test
	@DisplayName("규칙을 통과하면 바꾸고, 바꾸기 전 이름을 이력에 남기고, 바꾼 시각을 적는다")
	void changes() {
		MemberProfileResponse response = nicknameCommandService.change(MEMBER_ID, " 빵순이 ");

		assertThat(response.nickname()).isEqualTo("빵순이");
		assertThat(member.getNickname()).isEqualTo("빵순이");
		assertThat(member.getNicknameNormalized()).isEqualTo("빵순이");
		assertThat(member.getNicknameChangedAt()).isNotNull();
		ArgumentCaptor<NicknameHistory> history = ArgumentCaptor.forClass(NicknameHistory.class);
		then(nicknameHistoryRepository).should().save(history.capture());
		assertThat(history.getValue().getNickname()).isEqualTo("여린 바타르");
		then(nicknamePolicy).should().validate("빵순이");
	}

	@Test
	@DisplayName("같은 이름이면 아무것도 안 하고 200")
	void sameName() {
		nicknameCommandService.change(MEMBER_ID, "여린 바타르");

		then(nicknamePolicy).shouldHaveNoInteractions();
		then(nicknameHistoryRepository).shouldHaveNoInteractions();
		assertThat(member.getNicknameChangedAt()).isNull();
	}

	@Test
	@DisplayName("14일 안에 또 바꾸면 NICKNAME429_COOLDOWN. 번호가 붙은 회원(changedAt NULL)은 바로 된다")
	void cooldown() {
		ReflectionTestUtils.setField(member, "nicknameChangedAt", LocalDateTime.now().minusDays(13));

		assertThatThrownBy(() -> nicknameCommandService.change(MEMBER_ID, "빵순이"))
			.isInstanceOf(NicknameException.class)
			.extracting("errorCode").isEqualTo(NicknameErrorCode.NICKNAME_COOLDOWN);
		then(nicknamePolicy).shouldHaveNoInteractions();

		ReflectionTestUtils.setField(member, "nicknameChangedAt", LocalDateTime.now().minusDays(14));
		nicknameCommandService.change(MEMBER_ID, "빵순이");
		assertThat(member.getNickname()).isEqualTo("빵순이");
	}

	@Test
	@DisplayName("규칙 위반은 정책이 던진 그대로 나가고 아무것도 바뀌지 않는다")
	void policyRejects() {
		willThrow(new NicknameException(NicknameErrorCode.NICKNAME_TAKEN)).given(nicknamePolicy).validate("빵순이");

		assertThatThrownBy(() -> nicknameCommandService.change(MEMBER_ID, "빵순이"))
			.isInstanceOf(NicknameException.class);
		assertThat(member.getNickname()).isEqualTo("여린 바타르");
		then(nicknameHistoryRepository).should(never()).save(any());
	}

	@Test
	@DisplayName("작성 제한 · 정지 · 프로필 제한이면 403 그대로")
	void restricted() {
		willThrow(new MemberException(MemberErrorCode.MEMBER_PROFILE_RESTRICTED))
			.given(memberRestrictionReader).validateProfileNotRestricted(MEMBER_ID);

		assertThatThrownBy(() -> nicknameCommandService.change(MEMBER_ID, "빵순이"))
			.isInstanceOf(MemberException.class);
		assertThat(member.getNickname()).isEqualTo("여린 바타르");
	}
}
