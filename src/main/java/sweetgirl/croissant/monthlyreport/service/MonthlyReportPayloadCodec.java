package sweetgirl.croissant.monthlyreport.service;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import jakarta.validation.Validator;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;

@Component
public class MonthlyReportPayloadCodec {

	private final ObjectMapper mapper;
	private final Validator validator;

	public MonthlyReportPayloadCodec(ObjectMapper objectMapper, Validator validator) {
		this.mapper = objectMapper.copy().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
		this.mapper.coercionConfigFor(LogicalType.Integer)
			.setCoercion(CoercionInputShape.String, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.EmptyString, CoercionAction.Fail);
		this.mapper.coercionConfigFor(LogicalType.Textual)
			.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
			.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
		this.validator = validator;
	}

	public MonthlyReportResponse read(String payload, Long memberId, String yearMonth) {
		try {
			MonthlyReportResponse response = mapper.readValue(payload, MonthlyReportResponse.class);
			validate(response, memberId, yearMonth);
			return response;
		} catch (JsonProcessingException | IllegalArgumentException e) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT, e);
		}
	}

	public String write(MonthlyReportResponse response, Long memberId, String yearMonth) {
		validate(response, memberId, yearMonth);
		try {
			return mapper.writeValueAsString(response);
		} catch (JsonProcessingException e) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT, e);
		}
	}

	private void validate(MonthlyReportResponse response, Long memberId, String yearMonth) {
		if (response == null || !validator.validate(response).isEmpty()
			|| !response.memberId().equals(memberId) || !response.yearMonth().equals(yearMonth)
			|| !response.summary().records().equals(response.comparison().currentRecords())
			|| response.towns().stream().anyMatch(town -> town.region() != null && town.region().isBlank())) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
		var abilities = response.activity().abilities();
		if (abilities != null) {
			long sum = abilities.values().stream().mapToLong(MonthlyReportResponse.Ability::score).sum();
			if (sum > MonthlyReportResponse.MAX_SAFE_INTEGER
				|| abilities.values().stream().anyMatch(ability -> ability.score() > ability.maxScore())
				|| (response.activity().totalScore() != null && response.activity().totalScore() != sum)) {
				throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
			}
		}
		var artwork = response.activity().artwork();
		if (artwork != null && (!artwork.characterCode().equals(response.character().code())
			|| !artwork.planetCode().equals(response.planet().code())
			|| !artwork.type().equals(response.activity().type()))) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
	}
}
