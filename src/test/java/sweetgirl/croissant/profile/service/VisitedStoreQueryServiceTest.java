package sweetgirl.croissant.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.abuse.enums.AbuseReportTargetType;
import sweetgirl.croissant.abuse.service.ReportedTargetReader;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.follow.service.FollowVisibilityReader;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberBlockRepository;
import sweetgirl.croissant.member.service.SocialMemberReader;
import sweetgirl.croissant.profile.code.ProfileErrorCode;
import sweetgirl.croissant.profile.dto.response.VisitedStoreResponse;
import sweetgirl.croissant.profile.exception.ProfileException;
import sweetgirl.croissant.store.entity.Store;

@ExtendWith(MockitoExtension.class)
@DisplayName("VisitedStoreQueryService")
class VisitedStoreQueryServiceTest {

	private static final Long VIEWER = 1L;
	private static final Long TARGET = 2L;
	private static final GeometryFactory GEOMETRY = new GeometryFactory();

	@Mock
	private BreadRecordRepository breadRecordRepository;
	@Mock
	private BreadRecordPhotoRepository photoRepository;
	@Mock
	private BreadRecordTagRepository tagRepository;
	@Mock
	private SocialMemberReader socialMemberReader;
	@Mock
	private MemberBlockRepository memberBlockRepository;
	@Mock
	private ReportedTargetReader reportedTargetReader;
	@Mock
	private FollowVisibilityReader followVisibilityReader;

	@InjectMocks
	private VisitedStoreQueryService service;

	@org.junit.jupiter.api.BeforeEach
	void publicAccountByDefault() {
		lenient().when(followVisibilityReader.canViewRecords(any(), any())).thenReturn(true);
	}

	@Test
	@DisplayName("상대가 비공개 계정이고 내가 팔로워가 아니면 빈 목록")
	void privateAccountHidesStores() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(false);
		given(followVisibilityReader.canViewRecords(any(), any())).willReturn(false);

		assertThat(service.getVisitedStores(VIEWER, TARGET)).isEmpty();
		org.mockito.BDDMockito.then(breadRecordRepository).shouldHaveNoInteractions();
	}

	private Member member(Long id) {
		Member member = Member.builder().nickname("회원" + id).build();
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private Store store(Long id, String name) {
		Store store = mock(Store.class);
		lenient().when(store.getId()).thenReturn(id);
		lenient().when(store.getName()).thenReturn(name);
		lenient().when(store.getLocation())
			.thenReturn(GEOMETRY.createPoint(new Coordinate(127.05, 37.54)));
		return store;
	}

	private BreadRecord record(Long id, Store store) {
		BreadRecord record = BreadRecord.builder().member(member(TARGET)).store(store).storeName(store.getName())
			.eatenAt(LocalDateTime.of(2026, 10, 1, 12, 0)).isPublic(true).build();
		ReflectionTestUtils.setField(record, "id", id);
		return record;
	}

	private BreadRecordTag tag(BreadRecord record, String name) {
		return BreadRecordTag.builder().record(record).name(name).build();
	}

	@Test
	@DisplayName("가게별로 묶어 수 · 최근 사진 · 많이 적은 빵 2개를 주고, 내가 신고한 기록은 뺀다")
	void groupsByStore() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(false);
		Store mildo = store(5L, "밀도");
		Store sungsim = store(6L, "성심당");
		BreadRecord newest = record(30L, mildo);
		BreadRecord older = record(20L, mildo);
		BreadRecord other = record(10L, sungsim);
		BreadRecord reported = record(40L, sungsim);
		given(breadRecordRepository.findPublicWithStoreByMemberId(TARGET))
			.willReturn(List.of(reported, newest, older, other));
		given(reportedTargetReader.reportedIds(VIEWER, AbuseReportTargetType.RECORD)).willReturn(Set.of(40L));
		BreadRecordPhoto photo = BreadRecordPhoto.builder().record(newest).name("a").mimeType(MimeType.IMAGE_JPEG)
			.awsKey("bread-records/a").sortOrder(0).build();
		given(photoRepository.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(anyList()))
			.willReturn(List.of(photo));
		given(tagRepository.findByRecordIdInWithPhoto(anyList())).willReturn(List.of(
			tag(newest, "소금빵"), tag(older, "소금빵"), tag(older, "식빵"), tag(older, "크루아상"), tag(other, "튀김소보로")));

		List<VisitedStoreResponse> result = service.getVisitedStores(VIEWER, TARGET);

		assertThat(result).hasSize(2);
		VisitedStoreResponse first = result.get(0);
		assertThat(first.storeId()).isEqualTo(5L);
		assertThat(first.publicRecordCount()).isEqualTo(2);
		assertThat(first.latitude()).isEqualTo(37.54);
		assertThat(first.longitude()).isEqualTo(127.05);
		assertThat(first.thumbnailKey()).isEqualTo("bread-records/a");
		assertThat(first.topBreads()).containsExactly("소금빵", "식빵");
		assertThat(result.get(1).storeId()).isEqualTo(6L);
		assertThat(result.get(1).publicRecordCount()).isEqualTo(1);
		assertThat(result.get(1).thumbnailKey()).isNull();
	}

	@Test
	@DisplayName("상대가 없거나 봇 · 탈퇴면 404, 차단 관계면 403")
	void notFoundAndBlocked() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.empty());
		assertThatThrownBy(() -> service.getVisitedStores(VIEWER, TARGET))
			.isInstanceOf(ProfileException.class)
			.extracting("errorCode").isEqualTo(ProfileErrorCode.PROFILE_MEMBER_NOT_FOUND);

		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
		given(memberBlockRepository.existsBetween(VIEWER, TARGET)).willReturn(true);
		assertThatThrownBy(() -> service.getVisitedStores(VIEWER, TARGET))
			.extracting("errorCode").isEqualTo(ProfileErrorCode.PROFILE_BLOCKED);
	}

	@Test
	@DisplayName("공개 빵로그가 없으면 빈 목록 — 사진 · 태그를 조회하지 않는다")
	void empty() {
		given(socialMemberReader.findSocial(TARGET)).willReturn(Optional.of(member(TARGET)));
		given(breadRecordRepository.findPublicWithStoreByMemberId(TARGET)).willReturn(List.of());
		given(reportedTargetReader.reportedIds(any(), any())).willReturn(Set.of());

		assertThat(service.getVisitedStores(TARGET, TARGET)).isEmpty();
	}
}
