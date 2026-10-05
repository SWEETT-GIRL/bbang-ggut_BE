package sweetgirl.croissant.monthlyreport;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.BeanUtils;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import sweetgirl.croissant.member.entity.Member;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportMember;
import sweetgirl.croissant.monthlyreport.enums.MonthlyReportStatus;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportRepository.PublishedReport;

public final class MonthlyReportFixtures {

	public static final String MONTH = "2025-12";
	public static final String PAYLOAD = """
			{
			"schemaVersion": 1, "yearMonth": "2025-12", "memberId": 123, "nickname": "소금이",
			"character": {"code": "cat", "name": "냥이", "imageKey": null},
			"planet": {"code": "earth", "name": "지구"},
			"summary": {"records": 12, "breads": 18, "stores": 7},
			"stores": {"viewed": 24, "saved": 8, "planned": 4, "courses": 2},
			"breads": [{"name": "소금빵", "count": 8, "imageKey": "bread-records/members/123/images/a"}],
			"activity": {
			"type": "record", "totalScore": 72,
			"abilities": {
			"explore": {"score": 16, "maxScore": 25}, "save": {"score": 16, "maxScore": 25},
			"plan": {"score": 16, "maxScore": 25}, "record": {"score": 24, "maxScore": 25}
			},
			"artwork": {"characterCode": "cat", "planetCode": "earth", "type": "record",
			"version": "v1", "imageKey": "reports/types/v1/earth/cat/record.webp"}
			},
			"comparison": {"previousRecords": 9, "currentRecords": 12},
			"towns": [{"name": "성수동", "region": "서울 성동구"}]
			}
			""";

	private MonthlyReportFixtures() {
		// 테스트 예시 데이터만 제공한다.
	}

	public static MonthlyReportMember snapshot(String status, String payload) {
		MonthlyReportMember snapshot = BeanUtils.instantiateClass(MonthlyReportMember.class);
		ReflectionTestUtils.setField(snapshot, "status", MonthlyReportStatus.valueOf(status));
		ReflectionTestUtils.setField(snapshot, "payload", payload);
		if (payload != null) {
			try {
				var json = new ObjectMapper().readTree(payload);
				ReflectionTestUtils.setField(snapshot, "characterCode", json.at("/character/code").asText(null));
				ReflectionTestUtils.setField(snapshot, "characterName", json.at("/character/name").asText(null));
				ReflectionTestUtils.setField(snapshot, "characterImageKey",
					json.at("/character/imageKey").asText(null));
				ReflectionTestUtils.setField(snapshot, "planetCode", json.at("/planet/code").asText(null));
				ReflectionTestUtils.setField(snapshot, "planetName", json.at("/planet/name").asText(null));
			} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
				throw new IllegalArgumentException(e);
			}
		}
		return snapshot;
	}

	public static PublishedReport published(String month, Instant time) {
		return published(month, time, null);
	}

	public static PublishedReport published(String month, Instant time, String imageKey) {
		Map<String, Object> values = new HashMap<>(Map.of(
			"yearMonth", month, "publishedAt", time, "characterCode", "cat", "characterName", "냥이"));
		values.put("characterImageKey", imageKey);
		return new SpelAwareProxyProjectionFactory().createProjection(PublishedReport.class,
			values);
	}

	public static ObjectNode payload() throws Exception {
		return (ObjectNode)new ObjectMapper().readTree(PAYLOAD);
	}

	static Member eligibleMember(String nickname) {
		Member member = new Member(nickname);
		ReflectionTestUtils.setField(member, "id", 123L);
		ReflectionTestUtils.setField(member, "createdAt", LocalDateTime.of(2025, 1, 1, 0, 0));
		return member;
	}

}
