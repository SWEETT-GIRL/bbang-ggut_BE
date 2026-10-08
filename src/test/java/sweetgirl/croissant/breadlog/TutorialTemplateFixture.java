package sweetgirl.croissant.breadlog;

import java.util.List;

import sweetgirl.croissant.breadlog.dto.TutorialTemplateContent;
import sweetgirl.croissant.breadlog.dto.request.BreadTagRatingRequest;
import sweetgirl.croissant.breadlog.enums.TasteChip;
import sweetgirl.croissant.breadlog.enums.TextureChip;

public final class TutorialTemplateFixture {
	private TutorialTemplateFixture() {
		// 테스트 공용 템플릿.
	}

	public static TutorialTemplateContent content() {
		return new TutorialTemplateContent("빵댕이와 소금빵", "onboarding/tutorials/12345678-1234-1234-1234-123456789abc.png",
			"image/png", 1254, 1254, "빵댕이 빵집", "소금빵", .5, .65, "round.kraft",
			new BreadTagRatingRequest(true, List.of(TextureChip.겉바속촉), List.of(TasteChip.버터향),
				"바삭한 겉과 촉촉한 속, 버터향이 좋아요"),
			"빵댕이와 첫 빵로그 완성!", "croissant");
	}
}
