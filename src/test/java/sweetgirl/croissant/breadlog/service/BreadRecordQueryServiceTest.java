package sweetgirl.croissant.breadlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import sweetgirl.croissant.breadlog.code.BreadLogErrorCode;
import sweetgirl.croissant.breadlog.dto.response.BreadRecordResponse;
import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.entity.BreadRecordPhoto;
import sweetgirl.croissant.breadlog.entity.BreadRecordTag;
import sweetgirl.croissant.breadlog.exception.BreadLogException;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordRepository;
import sweetgirl.croissant.breadlog.repository.BreadRecordTagRepository;
import sweetgirl.croissant.common.enums.MimeType;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.service.MemberReader;

@ExtendWith(MockitoExtension.class)
class BreadRecordQueryServiceTest {

	@Mock
	MemberReader memberReader;
	@Mock
	BreadRecordRepository breadRecordRepository;
	@Mock
	BreadRecordPhotoRepository photoRepository;
	@Mock
	BreadRecordTagRepository tagRepository;

	@InjectMocks
	BreadRecordQueryService queryService;

	@Test
	@DisplayName("getRecords: 사진과 태그를 기록 ID 묶음으로 조회해 응답한다")
	void getRecords_loadsPhotosAndTagsInBulk() {
		BreadRecord record = record(11L);
		BreadRecordPhoto photo = BreadRecordPhoto.builder()
			.record(record)
			.name("a.jpg")
			.mimeType(MimeType.IMAGE_JPEG)
			.awsKey("bread-records/members/7/images/a")
			.sortOrder(0)
			.build();
		ReflectionTestUtils.setField(photo, "id", 21L);
		photo.markDone();
		photo.updateResizedKeys(photo.getAwsKey());
		BreadRecordTag tag = BreadRecordTag.builder()
			.record(record)
			.photo(photo)
			.name("우유식빵")
			.breadCategory("식빵")
			.positionX(0.31)
			.positionY(0.42)
			.build();
		ReflectionTestUtils.setField(tag, "id", 31L);
		given(breadRecordRepository.findAll(any(Specification.class), any(PageRequest.class)))
			.willReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 30), 1));
		given(photoRepository.findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(List.of(11L)))
			.willReturn(List.of(photo));
		given(tagRepository.findByRecordIdInWithPhoto(List.of(11L))).willReturn(List.of(tag));

		SliceResponse<BreadRecordResponse> result = queryService.getRecords(7L, null, null, null, 0, 30);

		assertThat(result.content()).hasSize(1);
		assertThat(result.hasNext()).isFalse();
		assertThat(result.content().get(0).photos()).hasSize(1);
		assertThat(result.content().get(0).photos().get(0).fileName()).isEqualTo("a.jpg");
		assertThat(result.content().get(0).photos().get(0).contentType()).isEqualTo("image/jpeg");
		assertThat(result.content().get(0).tags()).hasSize(1);
		assertThat(result.content().get(0).tags().get(0).positionX()).isEqualTo(0.31);
		assertThat(result.content().get(0).tags().get(0).breadCategory()).isEqualTo("식빵");
		verify(photoRepository).findByRecordIdInOrderByRecordIdAscSortOrderAscIdAsc(eq(List.of(11L)));
		verify(tagRepository).findByRecordIdInWithPhoto(eq(List.of(11L)));
	}

	@Test
	@DisplayName("getRecords: 시작 시각이 종료 시각보다 늦거나 같으면 거부한다")
	void getRecords_invalidDateRange_throwsException() {
		LocalDateTime from = LocalDateTime.of(2026, 8, 3, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		assertThatThrownBy(() -> queryService.getRecords(7L, from, to, null, 0, 30))
			.isInstanceOf(BreadLogException.class)
			.hasMessageContaining(BreadLogErrorCode.INVALID_DATE_RANGE.getMessage());
	}

	@Test
	@DisplayName("getRecords: 페이지 크기가 100을 넘으면 거부한다")
	void getRecords_oversizedPage_throwsException() {
		assertThatThrownBy(() -> queryService.getRecords(7L, null, null, null, 0, 101))
			.isInstanceOf(BreadLogException.class)
			.hasMessageContaining(BreadLogErrorCode.INVALID_PAGE_REQUEST.getMessage());
	}

	private BreadRecord record(Long id) {
		BreadRecord record = BreadRecord.builder()
			.member(Member.builder().nickname("테스터").build())
			.storeName("밀도 성수")
			.regionKey("서울_성동구_성수동")
			.eatenAt(LocalDateTime.of(2026, 8, 2, 14, 21, 7))
			.build();
		ReflectionTestUtils.setField(record, "id", id);
		ReflectionTestUtils.setField(record, "createdAt", LocalDateTime.of(2026, 8, 4, 12, 0));
		return record;
	}
}
