package sweetgirl.croissant.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import sweetgirl.croissant.admin.dto.response.AdminAuditLogResponse;
import sweetgirl.croissant.admin.service.AdminAuditLogQueryService;
import sweetgirl.croissant.global.advice.GlobalExceptionHandler;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogControllerTest {

	private MockMvc mockMvc;

	@Mock
	private AdminAuditLogQueryService queryService;

	@InjectMocks
	private AdminAuditLogController controller;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	@Test
	void search_returnsPagedLogs() throws Exception {
		AdminAuditLogResponse row = new AdminAuditLogResponse(1L, "dana", "POST", "/api/admin/members/12/ban", null,
			"MEMBER", "12", "{}", 200, "1.2.3.4", "admin-web", "req-1", null);
		given(queryService.search("MEMBER", "12", 0, 20))
			.willReturn(new AdminAuditLogResponse.PageResponse(List.of(row), 0, 20, 1, false));

		mockMvc.perform(get("/admin/audit-logs").param("targetType", "MEMBER").param("targetId", "12"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true))
			.andExpect(jsonPath("$.data.content[0].targetType").value("MEMBER"))
			.andExpect(jsonPath("$.data.content[0].adminLabel").value("dana"))
			.andExpect(jsonPath("$.data.totalElements").value(1));
	}

	@Test
	void search_rejectsNonNumericPage() throws Exception {
		mockMvc.perform(get("/admin/audit-logs").param("page", "first"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON400"));
	}
}
