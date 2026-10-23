package sweetgirl.croissant.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.follow.repository.MemberFollowRepository;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.profile.dto.response.AuthorCardResponse;
import sweetgirl.croissant.world.entity.WorldCharacter;
import sweetgirl.croissant.world.repository.MemberWorldRepository;
import sweetgirl.croissant.world.repository.WorldCharacterAssetRepository;
import sweetgirl.croissant.world.repository.WorldCharacterRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorCardReader")
class AuthorCardReaderTest {

	@Mock
	private MemberWorldRepository memberWorldRepository;
	@Mock
	private WorldCharacterRepository worldCharacterRepository;
	@Mock
	private WorldCharacterAssetRepository worldCharacterAssetRepository;
	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private MemberTitleReader memberTitleReader;
	@Mock
	private MemberFollowRepository memberFollowRepository;

	@InjectMocks
	private AuthorCardReader authorCardReader;

	@Test
	@DisplayName("세계관이 없으면 마스코트, 칭호 · 공개 수 · 팔로우 관계를 채운다. 본인이면 관계는 false")
	void reads() {
		Member author = Member.builder().nickname("작성자").build();
		ReflectionTestUtils.setField(author, "id", 1L);
		given(memberWorldRepository.findById(1L)).willReturn(Optional.empty());
		WorldCharacter mascot = mock(WorldCharacter.class);
		given(mascot.getCode()).willReturn("bbangdaeng");
		given(worldCharacterRepository.findByMascotTrue()).willReturn(Optional.of(mascot));
		given(worldCharacterAssetRepository.findById(org.mockito.ArgumentMatchers.any())).willReturn(Optional.empty());
		given(breadRecordRepository.countPublicByMemberId(1L)).willReturn(4L);
		given(memberTitleReader.titleOf(1L)).willReturn(Optional.of("성수동 빵순이"));
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(2L, 1L)).willReturn(true);
		given(memberFollowRepository.existsByIdFollowerIdAndIdFolloweeId(1L, 2L)).willReturn(false);

		AuthorCardResponse card = authorCardReader.read(2L, author);

		assertThat(card).isEqualTo(new AuthorCardResponse(1L, "작성자", "bbangdaeng", null, 4, "성수동 빵순이",
			false, true, false));

		AuthorCardResponse mine = authorCardReader.read(1L, author);
		assertThat(mine.isMe()).isTrue();
		assertThat(mine.followedByMe()).isFalse();
		then(memberFollowRepository).shouldHaveNoMoreInteractions();
	}
}
