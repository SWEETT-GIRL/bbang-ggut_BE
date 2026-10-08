package sweetgirl.croissant.breadlog.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import sweetgirl.croissant.breadlog.entity.BreadRecord;
import sweetgirl.croissant.breadlog.enums.RecordType;
import sweetgirl.croissant.breadlog.service.TutorialRecordTemplate;
import sweetgirl.croissant.config.JacksonConfig;

class BreadRecordResponseTest {
	@ParameterizedTest
	@EnumSource(RecordType.class)
	void preservesEntityTypeAndExistingJsonValue(RecordType type) throws Exception {
		BreadRecord record = BreadRecord.builder().build();
		if (type == RecordType.TUTORIAL) {
			record.markTutorial(TutorialRecordTemplate.CODE);
		}
		BreadRecordResponse response = BreadRecordResponse.of(record, List.of(), List.of(), "croissant");
		assertThat(response.recordType()).isEqualTo(type);
		var mapper = new JacksonConfig().objectMapper();
		var json = mapper.readTree(mapper.writeValueAsString(response));
		assertThat(json.get("recordType").isTextual()).isTrue();
		assertThat(json.get("recordType").asText()).isEqualTo(type.name());
	}
}
