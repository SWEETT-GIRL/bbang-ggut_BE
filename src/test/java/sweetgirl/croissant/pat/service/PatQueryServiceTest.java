package sweetgirl.croissant.pat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.pat.dto.response.PatListResponse;
import sweetgirl.croissant.pat.entity.MemberPat;
import sweetgirl.croissant.pat.entity.MemberPatId;
import sweetgirl.croissant.pat.repository.MemberPatRepository;
import sweetgirl.croissant.profile.dto.response.CharacterFace;
import sweetgirl.croissant.profile.service.CharacterFaceReader;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatQueryService")
class PatQueryServiceTest {

	private static final Long ME = 1L;
	private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Seoul"));

	@Mock
	private MemberPatRepository memberPatRepository;
	@Mock
	private CharacterFaceReader characterFaceReader;

	@InjectMocks
	private PatQueryService patQueryService;

	private MemberPat pat(Long fromId, LocalDate date) {
		Member sender = Member.builder().nickname("회원" + fromId).build();
		ReflectionTestUtils.setField(sender, "id", fromId);
		MemberPat pat = BeanUtils.instantiateClass(MemberPat.class);
		ReflectionTestUtils.setField(pat, "id", new MemberPatId(fromId, ME, date));
		ReflectionTestUtils.setField(pat, "fromMember", sender);
		ReflectionTestUtils.setField(pat, "createdAt", LocalDateTime.now());
		return pat;
	}

	@Test
	@DisplayName("오늘 요약은 가장 최근 사람이 앞이고 count 는 오늘 친 사람 수, 목록엔 얼굴이 붙는다")
	void received() {
		given(memberPatRepository.findReceivedSince(eq(ME), any()))
			.willReturn(List.of(pat(3L, TODAY), pat(2L, TODAY), pat(2L, TODAY.minusDays(1))));
		given(characterFaceReader.facesOf(anyCollection()))
			.willReturn(Map.of(3L, new CharacterFace("CROISSANT", "world/croissant.png"), 2L, CharacterFace.NONE));

		PatListResponse response = patQueryService.getReceived(ME, 7);

		assertThat(response.today().firstMemberId()).isEqualTo(3L);
		assertThat(response.today().firstNickname()).isEqualTo("회원3");
		assertThat(response.today().count()).isEqualTo(2);
		assertThat(response.items()).hasSize(3);
		assertThat(response.items().get(0).characterCode()).isEqualTo("CROISSANT");
		assertThat(response.items().get(1).characterCode()).isNull();
	}

	@Test
	@DisplayName("오늘 받은 게 없으면 today 는 null")
	void noneToday() {
		given(memberPatRepository.findReceivedSince(eq(ME), any()))
			.willReturn(List.of(pat(2L, TODAY.minusDays(2))));
		given(characterFaceReader.facesOf(anyCollection())).willReturn(Map.of());

		PatListResponse response = patQueryService.getReceived(ME, 7);

		assertThat(response.today()).isNull();
		assertThat(response.items()).hasSize(1);
	}

	@Test
	@DisplayName("days 는 최대 30 — 7 이면 오늘 포함 7일 전부터")
	void sinceIsClamped() {
		given(memberPatRepository.findReceivedSince(ME, TODAY.minusDays(6))).willReturn(List.of());
		given(characterFaceReader.facesOf(anyCollection())).willReturn(Map.of());

		assertThat(patQueryService.getReceived(ME, 7).items()).isEmpty();

		given(memberPatRepository.findReceivedSince(ME, TODAY.minusDays(29))).willReturn(List.of());
		assertThat(patQueryService.getReceived(ME, 999).items()).isEmpty();
	}
}
