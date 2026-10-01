package sweetgirl.croissant.monthlyreport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.MONTH;
import static sweetgirl.croissant.monthlyreport.MonthlyReportFixtures.PAYLOAD;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;
import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.member.repository.MemberRepository;
import sweetgirl.croissant.member.service.MemberReader;
import sweetgirl.croissant.monthlyreport.controller.MonthlyReportController;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportListItem;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository.Snapshot;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportPayloadCodec;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportQueryService;

@ExtendWith(MockitoExtension.class)
class MonthlyReportControllerTest {

	private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
	@Mock
	private MonthlyReportRepository repository;
	@Mock
	private MemberRepository memberRepository;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		var service = new MonthlyReportQueryService(new MemberReader(memberRepository), repository,
			new MonthlyReportPayloadCodec(new ObjectMapper(), FACTORY.getValidator()));
		mvc = MockMvcBuilders.standaloneSetup(new MonthlyReportController(service))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@AfterAll
	static void closeFactory() {
		FACTORY.close();
	}

	private void authenticate() {
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(123L, null, List.of()));
		given(memberRepository.findById(123L)).willReturn(Optional.of(new Member("소금이")));
	}

	@Test
	void respondsWithOwnSnapshotAndIgnoresSuppliedMemberId() throws Exception {
		authenticate();
		given(repository.find(123L, MONTH)).willReturn(Optional.of(new Snapshot("PUBLISHED", PAYLOAD)));
		given(repository.findVisiblePhotoKeys(eq(123L), any()))
			.willReturn(Set.of("bread-records/members/123/images/a"));
		mvc.perform(get("/reports/monthly/{month}/me", MONTH).param("memberId", "456"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.data.memberId").value(123))
			.andExpect(jsonPath("$.data.yearMonth").value(MONTH))
			.andExpect(jsonPath("$.data.activity.totalScore").value(72))
			.andExpect(jsonPath("$.data.breads[0].imageKey").value("bread-records/members/123/images/a"));
		verify(repository, never()).find(eq(456L), any());
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isUnauthorized());
		verify(repository, never()).find(any(), any());
	}

	@Test
	void invalidMonthIs400BeforeQuery() throws Exception {
		authenticate();
		mvc.perform(get("/reports/monthly/2026-13/me")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("MONTHLY_REPORT4001"));
		verify(repository, never()).find(any(), any());
	}

	@Test
	void absentAndWithheldSnapshotsAre404() throws Exception {
		authenticate();
		given(repository.find(123L, MONTH)).willReturn(Optional.empty(), Optional.of(new Snapshot("WITHHELD", null)));
		for (int i = 0; i < 2; i++) {
			mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MONTHLY_REPORT4041"));
		}
	}

	@Test
	void failedAggregationIs503() throws Exception {
		authenticate();
		given(repository.find(123L, MONTH)).willReturn(Optional.of(new Snapshot("FAILED", null)));
		mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("MONTHLY_REPORT5031"));
	}

	@Test
	void mismatchedPayloadIs500WithoutLeakingAnotherMember() throws Exception {
		authenticate();
		given(repository.find(123L, MONTH))
			.willReturn(Optional.of(new Snapshot("PUBLISHED", PAYLOAD.replace("123", "456"))));
		mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("MONTHLY_REPORT5001")).andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void databaseFailureIsNotDisguisedAsMissingReport() throws Exception {
		authenticate();
		given(repository.find(123L, MONTH))
			.willThrow(new DataAccessResourceFailureException("test database unavailable"));
		mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("COMMON500"));
	}

	@Test
	void deletedOrForeignPhotosAreRemovedWithoutChangingCounts() throws Exception {
		authenticate();
		given(repository.find(123L, MONTH)).willReturn(Optional.of(new Snapshot("PUBLISHED", PAYLOAD)));
		given(repository.findVisiblePhotoKeys(eq(123L), any())).willReturn(Set.of());
		mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.breads[0].imageKey").isEmpty())
			.andExpect(jsonPath("$.data.breads[0].count").value(8))
			.andExpect(jsonPath("$.data.summary.records").value(12));
	}

	@Test
	void nullAbilitiesRemainExplicitInHttpResponse() throws Exception {
		authenticate();
		var payload = MonthlyReportFixtures.payload();
		payload.withObject("/activity").putNull("abilities").putNull("totalScore");
		payload.putArray("breads");
		given(repository.find(123L, MONTH)).willReturn(Optional.of(new Snapshot("PUBLISHED", payload.toString())));
		mvc.perform(get("/reports/monthly/{month}/me", MONTH)).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.activity.totalScore").hasJsonPath())
			.andExpect(jsonPath("$.data.activity.totalScore").isEmpty())
			.andExpect(jsonPath("$.data.activity.abilities").isEmpty());
	}

	@Test
	void listsOwnPublishedMonthsWithLinksAndPagination() throws Exception {
		authenticate();
		Instant publishedAt = Instant.parse("2026-01-01T01:00:00Z");
		given(repository.findPublished(123L, 0L, 3)).willReturn(List.of(
			MonthlyReportListItem.of("2025-12", publishedAt), MonthlyReportListItem.of("2025-11", publishedAt),
			MonthlyReportListItem.of("2025-10", publishedAt)));
		mvc.perform(get("/reports/monthly/me").param("size", "2").param("memberId", "456"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.data.content.length()").value(2))
			.andExpect(jsonPath("$.data.content[0].yearMonth").value("2025-12"))
			.andExpect(jsonPath("$.data.content[0].publishedAt").value("2026-01-01T01:00:00Z"))
			.andExpect(jsonPath("$.data.content[0].link").value("/reports/monthly/2025-12"))
			.andExpect(jsonPath("$.data.hasNext").value(true));
		verify(repository, never()).findPublished(eq(456L), any(Long.class), any(Integer.class));
	}

	@Test
	void emptyListIs200AndUsesDefaultPageSize() throws Exception {
		authenticate();
		given(repository.findPublished(123L, 0L, 13)).willReturn(List.of());
		mvc.perform(get("/reports/monthly/me")).andExpect(status().isOk())
			.andExpect(jsonPath("$.data.content").isEmpty()).andExpect(jsonPath("$.data.hasNext").value(false));
	}

	@Test
	void listRequiresAuthenticationAndRejectsInvalidPagination() throws Exception {
		mvc.perform(get("/reports/monthly/me")).andExpect(status().isUnauthorized());
		authenticate();
		for (String[] params : List.of(new String[] {"-1", "12"}, new String[] {"0", "0"}, new String[] {"0", "101"})) {
			mvc.perform(get("/reports/monthly/me").param("page", params[0]).param("size", params[1]))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MONTHLY_REPORT4002"));
		}
		mvc.perform(get("/reports/monthly/me").param("page", "bad")).andExpect(status().isBadRequest());
	}

	@Test
	void listSecondPageUsesOffsetAndHasNoNextPage() throws Exception {
		authenticate();
		given(repository.findPublished(123L, 12L, 13)).willReturn(List.of(
			MonthlyReportListItem.of("2024-12", Instant.parse("2025-01-01T01:00:00Z"))));
		mvc.perform(get("/reports/monthly/me").param("page", "1"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.hasNext").value(false))
			.andExpect(jsonPath("$.data.content[0].yearMonth").value("2024-12"));
	}
}
