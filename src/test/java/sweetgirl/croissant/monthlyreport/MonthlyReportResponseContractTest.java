package sweetgirl.croissant.monthlyreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sweetgirl.croissant.config.JacksonConfig;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.global.response.SliceResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportMemberResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportMemberStatusResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportOperationsResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportProgressResponse;
import sweetgirl.croissant.monthlyreport.admin.dto.MonthlyReportRunResponse;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportDraft;
import sweetgirl.croissant.monthlyreport.admin.entity.MonthlyReportRun;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportListItem;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportNotificationRepository.MemberStatus;
import sweetgirl.croissant.monthlyreport.service.MonthlyReportActivityPolicy;

/** 실제 서버 Jackson 설정으로 내보낸 응답을 앱·어드민 파서와 대조할 수 있다. */
class MonthlyReportResponseContractTest {
	private final ObjectMapper mapper = new JacksonConfig().objectMapper();

	@Test
	void serializedReportsKeepExplicitNullsAndOmitVersionTwoCaps() throws Exception {
		MonthlyReportResponse legacy = mapper.readValue(MonthlyReportFixtures.PAYLOAD, MonthlyReportResponse.class);
		export("detail-v1", legacy);
		MonthlyReportResponse power = new MonthlyReportActivityPolicy().applyTo(legacy, mapper.createObjectNode());
		ObjectNode payload = mapper.valueToTree(power);
		payload.put("coverImageKey", "monthly-reports/assets/v1/covers/cat/12.png");
		((ObjectNode)payload.get("comparison")).putNull("previousRecords");
		MonthlyReportResponse report = mapper.treeToValue(payload, MonthlyReportResponse.class);
		JsonNode json = export("detail-v2", report).get("data");
		assertThat(json.at("/comparison/previousRecords").isNull()).isTrue();
		assertThat(json.at("/activity/abilities/record").has("maxScore")).isFalse();
		assertThat(json.get("coverImageKey").asText()).endsWith("/12.png");
		export("list", new SliceResponse<>(List.of(MonthlyReportListItem.of(report.yearMonth(),
			Instant.parse("2026-01-01T01:00:00Z"), report.character())), false));
	}

	@Test
	void adminResponsesPreserveNullableFieldsButEmptyEnvelopeOmitsData() throws Exception {
		MonthlyReportRun run = MonthlyReportRun.create("2025-12", "v1");
		JsonNode json = export("run", MonthlyReportRunResponse.from(run)).get("data");
		assertThat(json.get("pushAt").isNull()).isTrue();
		assertThat(json.get("errorCode").isNull()).isTrue();
		assertThat(json.get("scanComplete").isBoolean()).isTrue();
		export("progress", new MonthlyReportProgressResponse(MonthlyReportRunResponse.from(run), Map.of(
			MonthlyReportDraft.State.PENDING, 0L, MonthlyReportDraft.State.PREPARED, 1L,
			MonthlyReportDraft.State.FAILED, 0L, MonthlyReportDraft.State.PUBLISHED, 0L,
			MonthlyReportDraft.State.SKIPPED, 0L, MonthlyReportDraft.State.PUBLISH_FAILED, 0L)));
		export("members", new SliceResponse<>(List.of(new MonthlyReportMemberResponse(123L,
			MonthlyReportDraft.State.PREPARED, 1, null)), false));
		MemberStatus status = new SpelAwareProxyProjectionFactory().createProjection(MemberStatus.class,
			Map.of("memberId", 123L, "pushState", "NOT_SCHEDULED", "totalAttempts", 0, "manualRetries", 0));
		JsonNode operations = export("operations", new MonthlyReportOperationsResponse(1, 0, 0,
			Map.of("NOT_SCHEDULED", 1L),
			new SliceResponse<>(List.of(MonthlyReportMemberStatusResponse.from(status)), false)));
		assertThat(operations.at("/data/members/content/0/firstViewedAt").isNull()).isTrue();
		assertThat(export("retry-push", null).has("data")).isFalse();
	}

	private JsonNode export(String name, Object data) throws Exception {
		byte[] bytes = mapper.writeValueAsBytes(ApiResponse.ok(data));
		Path directory = Path.of("build", "monthly-report-contract");
		Files.createDirectories(directory);
		Files.write(directory.resolve(name + ".json"), bytes);
		return mapper.readTree(bytes);
	}
}
