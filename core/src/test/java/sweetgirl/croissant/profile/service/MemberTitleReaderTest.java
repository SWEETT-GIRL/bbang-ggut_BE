package sweetgirl.croissant.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTownProjection;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberTitleReader")
class MemberTitleReaderTest {

	@Mock
	private BreadRecordRepository breadRecordRepository;

	@InjectMocks
	private MemberTitleReader memberTitleReader;

	private BreadRecordTownProjection town(String regionKey) {
		return new BreadRecordTownProjection() {
			@Override
			public String getRegionKey() {
				return regionKey;
			}

			@Override
			public long getRecordCount() {
				return 3;
			}
		};
	}

	@Test
	@DisplayName("공개 빵로그가 가장 많은 동네로 'OO동 빵순이'")
	void title() {
		given(breadRecordRepository.findTopPublicTown(1L, MemberTitleReader.MIN_PUBLIC_RECORDS))
			.willReturn(Optional.of(town("서울_성동구_성수동")));

		assertThat(memberTitleReader.titleOf(1L)).contains("성수동 빵순이");
	}

	@Test
	@DisplayName("3개 미만이거나 동 조각이 없는 동네(시도_구)면 칭호가 없다")
	void noTitle() {
		given(breadRecordRepository.findTopPublicTown(1L, MemberTitleReader.MIN_PUBLIC_RECORDS))
			.willReturn(Optional.empty());
		assertThat(memberTitleReader.titleOf(1L)).isEmpty();

		given(breadRecordRepository.findTopPublicTown(1L, MemberTitleReader.MIN_PUBLIC_RECORDS))
			.willReturn(Optional.of(town("서울_성동구")));
		assertThat(memberTitleReader.titleOf(1L)).isEmpty();
	}

	@Test
	@DisplayName("동 이름은 읍 · 면 · 동 · 리 · 가로 끝나야 하고 두 조각(세종_도담동)도 된다")
	void townOf() {
		assertThat(MemberTitleReader.townOf("세종_도담동")).contains("도담동");
		assertThat(MemberTitleReader.townOf("서울_종로구_종로1가")).contains("종로1가");
		assertThat(MemberTitleReader.townOf("서울_성동구_")).isEmpty();
		assertThat(MemberTitleReader.townOf("대전_중구_은행동1")).isEmpty();
		assertThat(MemberTitleReader.townOf(null)).isEmpty();
	}
}
