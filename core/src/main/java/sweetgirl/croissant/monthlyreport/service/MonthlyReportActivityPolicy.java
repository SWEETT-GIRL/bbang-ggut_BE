package sweetgirl.croissant.monthlyreport.service;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.node.ObjectNode;

import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Abilities;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Ability;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Activity;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Artwork;

/** 월간 활동 기여도를 빵력으로 계산한다. 화면의 게이지 길이는 별도 표현 정책이다. */
@Component
public class MonthlyReportActivityPolicy {

	public static final String VERSION = "bread-power-v1";
	private static final long EXPLORE_WEIGHT = 1;
	private static final long SAVE_WEIGHT = 3;
	private static final long PLAN_WEIGHT = 5;
	private static final long RECORD_WEIGHT = 7;

	public MonthlyReportResponse applyTo(MonthlyReportResponse report, ObjectNode metrics) {
		Activity calculated = calculate(report.stores().viewed(), report.stores().saved(), report.stores().planned(),
			report.summary().records());
		Artwork artwork = report.activity().artwork();
		if (artwork != null && !artwork.type().equals(calculated.type())) {
			artwork = null;
		}
		Activity activity = new Activity(calculated.type(), calculated.totalScore(), calculated.abilities(), artwork);
		ObjectNode policy = metrics.putObject("activityPolicy");
		policy.put("version", VERSION).put("type", activity.type()).put("totalScore", activity.totalScore())
			.put("tieOrder", "record,plan,save,explore");
		policy.putObject("weights").put("explore", EXPLORE_WEIGHT).put("save", SAVE_WEIGHT)
			.put("plan", PLAN_WEIGHT).put("record", RECORD_WEIGHT);
		policy.putObject("contributions").put("explore", activity.abilities().explore().score())
			.put("save", activity.abilities().save().score()).put("plan", activity.abilities().plan().score())
			.put("record", activity.abilities().record().score());
		return new MonthlyReportResponse(2, report.yearMonth(), report.memberId(), report.nickname(),
			report.character(), report.planet(), report.summary(), report.stores(), report.breads(), activity,
			report.comparison(), report.towns());
	}

	public Activity calculate(long viewed, long saved, long planned, long records) {
		long explore = contribution(viewed, EXPLORE_WEIGHT);
		long save = contribution(saved, SAVE_WEIGHT);
		long plan = contribution(planned, PLAN_WEIGHT);
		long record = contribution(records, RECORD_WEIGHT);
		long total = explore + save + plan + record;
		if (total > MonthlyReportResponse.MAX_SAFE_INTEGER) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		long highest = Math.max(Math.max(explore, save), Math.max(plan, record));
		String type;
		if (highest == 0) {
			type = "seed";
		} else if (record == highest) {
			type = "record";
		} else if (plan == highest) {
			type = "plan";
		} else if (save == highest) {
			type = "save";
		} else {
			type = "explore";
		}
		return new Activity(type, total, new Abilities(new Ability(explore, null), new Ability(save, null),
			new Ability(plan, null), new Ability(record, null)), null);
	}

	private long contribution(long count, long weight) {
		// 사업상 상한이 아니라 JSON 정수 정밀도 검증이다. 잘못된 원천은 자르지 않고 발행을 실패시킨다.
		if (count < 0 || count > MonthlyReportResponse.MAX_SAFE_INTEGER / weight) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		return count * weight;
	}
}
