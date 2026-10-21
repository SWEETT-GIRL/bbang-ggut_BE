package sweetgirl.croissant.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import sweetgirl.croissant.admin.dto.response.AdminAuditLogResponse;
import sweetgirl.croissant.admin.entity.AdminAuditLog;
import sweetgirl.croissant.admin.repository.AdminAuditLogRepository;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogQueryServiceTest {

	@Mock
	private AdminAuditLogRepository repository;

	@InjectMocks
	private AdminAuditLogQueryService queryService;

	@Test
	@DisplayName("대상 종류는 대문자로 맞추고, 최신순 · 크기는 100 으로 자른다")
	void search_normalizesFiltersAndPaging() {
		given(repository.search(eq("MEMBER"), eq("12"), any())).willReturn(
			new PageImpl<>(List.of(AdminAuditLog.builder().method("POST").path("/api/admin/members/12/ban").build())));

		AdminAuditLogResponse.PageResponse response = queryService.search(" member ", " 12 ", -1, 500);

		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(repository).search(eq("MEMBER"), eq("12"), pageable.capture());
		assertThat(pageable.getValue().getPageNumber()).isZero();
		assertThat(pageable.getValue().getPageSize()).isEqualTo(AdminAuditLogQueryService.MAX_SIZE);
		assertThat(pageable.getValue().getSort().getOrderFor("id").isDescending()).isTrue();
		assertThat(response.content()).extracting(AdminAuditLogResponse::path)
			.containsExactly("/api/admin/members/12/ban");
	}

	@Test
	@DisplayName("빈 필터는 거르지 않는다")
	void search_blankFiltersMeanAll() {
		given(repository.search(isNull(), isNull(), any())).willReturn(Page.empty());

		AdminAuditLogResponse.PageResponse response = queryService.search("", null, 0, 20);

		assertThat(response.content()).isEmpty();
		assertThat(response.hasNext()).isFalse();
	}
}
