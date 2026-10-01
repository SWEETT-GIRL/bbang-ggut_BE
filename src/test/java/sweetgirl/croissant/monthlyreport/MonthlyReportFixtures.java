package sweetgirl.croissant.monthlyreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

	public static ObjectNode payload() throws Exception {
		return (ObjectNode)new ObjectMapper().readTree(PAYLOAD);
	}
}
