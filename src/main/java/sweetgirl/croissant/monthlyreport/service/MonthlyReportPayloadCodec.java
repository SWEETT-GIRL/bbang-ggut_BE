package sweetgirl.croissant.monthlyreport.service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import jakarta.validation.Validator;
import sweetgirl.croissant.global.exception.CustomException;
import sweetgirl.croissant.monthlyreport.code.MonthlyReportErrorCode;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportPhotoSource;
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

	public Map<String, MonthlyReportPhotoSource> readPhotoSources(String rawMetrics) {
		if (rawMetrics == null) {
			return Map.of();
		}
		try {
			JsonNode root = mapper.readTree(rawMetrics);
			if (root == null || !root.isObject()) {
				throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
			}
			JsonNode sources = root.get("breadPhotoSources");
			if (sources == null) {
				return Map.of();
			}
			if (!sources.isObject() || sources.size() > 100) {
				throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
			}
			Map<String, MonthlyReportPhotoSource> result = new HashMap<>();
			for (Map.Entry<String, JsonNode> field : sources.properties()) {
				MonthlyReportPhotoSource source = mapper.treeToValue(field.getValue(), MonthlyReportPhotoSource.class);
				if (field.getKey().isBlank() || field.getKey().length() > 100 || source == null
					|| !validator.validate(source).isEmpty()) {
					throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
				}
				result.put(field.getKey(), source);
			}
			return Map.copyOf(result);
		} catch (JsonProcessingException | IllegalArgumentException e) {
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
		MonthlyReportResponse.Abilities abilities = response.activity().abilities();
		if (abilities != null) {
			long sum = abilities.values().stream().mapToLong(MonthlyReportResponse.Ability::score).sum();
			if (sum > MonthlyReportResponse.MAX_SAFE_INTEGER
				|| abilities.values().stream().anyMatch(ability -> ability.score() > ability.maxScore())
				|| (response.activity().totalScore() != null && response.activity().totalScore() != sum)) {
				throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
			}
		}
		MonthlyReportResponse.Artwork artwork = response.activity().artwork();
		if (artwork != null && (!artwork.characterCode().equals(response.character().code())
			|| !artwork.planetCode().equals(response.planet().code())
			|| !artwork.type().equals(response.activity().type()))) {
			throw new CustomException(MonthlyReportErrorCode.INVALID_SNAPSHOT);
		}
	}
}
