package sweetgirl.croissant.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import sweetgirl.croissant.admin.service.AdminCourseCommandService;
import sweetgirl.croissant.breadlog.repository.BreadRecordPhotoRepository;
import sweetgirl.croissant.common.enums.ImageStatus;
import sweetgirl.croissant.config.cache.CacheConfig;
import sweetgirl.croissant.contribution.repository.MenuContributionReportImageFileRepository;
import sweetgirl.croissant.contribution.repository.StoreContributionReportImageFileRepository;
import sweetgirl.croissant.course.code.CourseCacheNames;
import sweetgirl.croissant.course.dto.request.AdminCourseCreateRequest;
import sweetgirl.croissant.course.dto.request.CourseCreateRequest;
import sweetgirl.croissant.course.entity.Course;
import sweetgirl.croissant.course.repository.CourseImageFileRepository;
import sweetgirl.croissant.course.repository.CourseProgressRepository;
import sweetgirl.croissant.course.repository.CourseRepository;
import sweetgirl.croissant.course.repository.CourseStoreRepository;
import sweetgirl.croissant.course.repository.SavedCourseRepository;
import sweetgirl.croissant.external.aws.S3Service;
import sweetgirl.croissant.external.aws.dto.ImageStatusUpdateRequest;
import sweetgirl.croissant.external.aws.metrics.ImagePipelineMetrics;
import sweetgirl.croissant.external.aws.service.ImageCallbackService;
import sweetgirl.croissant.external.aws.service.ImageProcessingCallbackService;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.menu.repository.MenuImageFileRepository;
import sweetgirl.croissant.review.repository.ReviewImageFileRepository;
import sweetgirl.croissant.store.repository.StoreImageFileRepository;
import sweetgirl.croissant.store.repository.StoreRepository;

/**
 * 공개 코스 목록 캐시의 히트·무효화가 프록시를 통해 실제로 동작하는지 검증한다.
 * Redis 대신 인메모리 캐시를 쓴다 — 여기서 보는 것은 애노테이션 배선이지 Redis 자체가 아니다.
 */
@SpringJUnitConfig
@Import({CacheConfig.class, CourseQueryService.class, CourseCommandService.class,
	AdminCourseCommandService.class, ImageCallbackService.class})
@MockitoBean(types = {CourseRepository.class, CourseStoreRepository.class, SavedCourseRepository.class,
	CourseImageFileRepository.class, CourseProgressRepository.class, MemberRepository.class, StoreRepository.class,
	S3Service.class, ImageProcessingCallbackService.class, CourseProgressService.class,
	StoreImageFileRepository.class, MenuImageFileRepository.class, StoreContributionReportImageFileRepository.class,
	MenuContributionReportImageFileRepository.class, ReviewImageFileRepository.class,
	BreadRecordPhotoRepository.class, ImagePipelineMetrics.class})
class CoursePublicListCacheTest {

	@Configuration
	static class InMemoryCacheConfig {
		@Bean
		CacheManager cacheManager() {
			return new ConcurrentMapCacheManager(CourseCacheNames.PUBLIC_LIST);
		}
	}

	@Autowired
	private CourseQueryService courseQueryService;
	@Autowired
	private CourseCommandService courseCommandService;
	@Autowired
	private AdminCourseCommandService adminCourseCommandService;
	@Autowired
	private ImageCallbackService imageCallbackService;
	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private CourseRepository courseRepository;
	@Autowired
	private MemberRepository memberRepository;

	@BeforeEach
	void setUp() {
		cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).clear();
		when(courseRepository.findAllPublicOrderedByPinAndSavedCount()).thenReturn(List.of());
		when(courseRepository.save(any(Course.class))).thenReturn(mock(Course.class));
	}

	@Test
	@DisplayName("공개 코스 목록은 두 번째 호출부터 캐시에서 나가고 DB는 한 번만 조회한다")
	void getCurationCourses_hitsCacheOnSecondCall() {
		courseQueryService.getCurationCourses();
		courseQueryService.getCurationCourses();

		verify(courseRepository, times(1)).findAllPublicOrderedByPinAndSavedCount();
		assertThat(cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).get("all")).isNotNull();
	}

	@Test
	@DisplayName("어드민 큐레이션 코스 명령은 목록 캐시를 비운다")
	void adminCommand_evictsPublicList() {
		primeCache();
		AdminCourseCreateRequest request = mock(AdminCourseCreateRequest.class);
		when(request.getTitle()).thenReturn("부산 빵투어");

		adminCourseCommandService.createCuratedCourse(request);

		assertThat(cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).get("all")).isNull();
		courseQueryService.getCurationCourses();
		verify(courseRepository, times(2)).findAllPublicOrderedByPinAndSavedCount();
	}

	@Test
	@DisplayName("유저 코스 명령도 목록 캐시를 비운다 — 공개 코스는 유저가 만들 수 있다")
	void userCommand_evictsPublicList() {
		primeCache();
		when(memberRepository.findByIdAndDeletedAtIsNull(anyLong())).thenReturn(Optional.of(mock(Member.class)));
		CourseCreateRequest request = mock(CourseCreateRequest.class);
		when(request.getTitle()).thenReturn("내 코스");
		when(request.isPublic()).thenReturn(true);

		courseCommandService.createCourse(1L, request);

		assertThat(cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).get("all")).isNull();
	}

	@Test
	@DisplayName("코스 커버 이미지 콜백은 목록 캐시를 비우지만, 다른 이미지 콜백은 건드리지 않는다")
	void imageCallback_evictsOnlyForCourseCovers() {
		primeCache();
		imageCallbackService.updateImageStatus(callback("stores/1/photo.jpg"));
		assertThat(cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).get("all")).isNotNull();

		imageCallbackService.updateImageStatus(callback("courses/1/cover.jpg"));
		assertThat(cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).get("all")).isNull();
	}

	private void primeCache() {
		courseQueryService.getCurationCourses();
		assertThat(cacheManager.getCache(CourseCacheNames.PUBLIC_LIST).get("all")).isNotNull();
	}

	private ImageStatusUpdateRequest callback(String awsKey) {
		ImageStatusUpdateRequest request = mock(ImageStatusUpdateRequest.class);
		when(request.getAwsKey()).thenReturn(awsKey);
		when(request.getStatus()).thenReturn(ImageStatus.DONE);
		return request;
	}
}
