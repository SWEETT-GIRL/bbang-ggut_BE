package sweetgirl.croissant.monthlyreport.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Activity;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Artwork;
import sweetgirl.croissant.monthlyreport.dto.MonthlyReportResponse.Character;
import sweetgirl.croissant.monthlyreport.entity.MonthlyReportAssetReplacement;
import sweetgirl.croissant.monthlyreport.repository.MonthlyReportAssetReplacementRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyReportAssetImageQueryService {
	private final MonthlyReportAssetReplacementRepository replacements;

	public Map<String, String> getReplacementKeys(Collection<String> imageKeys) {
		List<String> keys = imageKeys.stream().filter(Objects::nonNull).distinct().toList();
		if (keys.isEmpty()) {
			return Map.of();
		}
		return replacements.findAllById(keys).stream().collect(Collectors.toMap(
			MonthlyReportAssetReplacement::getOriginalImageKey, MonthlyReportAssetReplacement::getImageKey));
	}

	public MonthlyReportResponse resolveReportImages(MonthlyReportResponse report) {
		Character character = report.character();
		Activity activity = report.activity();
		Artwork artwork = activity.artwork();
		Map<String, String> keys = getReplacementKeys(Stream.of(report.coverImageKey(), character.faceImageKey(),
			artwork == null ? null : artwork.imageKey()).toList());
		Character visibleCharacter = new Character(character.code(), character.name(), character.imageKey(),
			replaceKey(keys, character.faceImageKey()));
		Artwork visibleArtwork = artwork == null ? null : new Artwork(artwork.characterCode(), artwork.planetCode(),
			artwork.type(), artwork.version(), replaceKey(keys, artwork.imageKey()));
		return new MonthlyReportResponse(report.schemaVersion(), report.yearMonth(), report.memberId(),
			report.nickname(),
			visibleCharacter, report.planet(), report.summary(), report.stores(), report.breads(),
			new Activity(activity.type(), activity.totalScore(), activity.abilities(), visibleArtwork),
			report.comparison(), report.towns(), replaceKey(keys, report.coverImageKey()));
	}

	public static String replaceKey(Map<String, String> replacements, String imageKey) {
		return imageKey == null ? null : replacements.getOrDefault(imageKey, imageKey);
	}
}
