package sweetgirl.croissant.breadlog.dto.request;

import java.util.List;

import jakarta.validation.constraints.Size;
import sweetgirl.croissant.breadranking.enums.TasteChip;
import sweetgirl.croissant.breadranking.enums.TextureChip;

/**
 * 태그 하나의 빵 평가("빵은 어땠어요?" 단계). 전부 건너뛸 수 있다 — 칸마다 null·빈 목록이면 안 고른 것.
 * 칩은 enum 이라 목록 밖 값은 400 이다.
 *
 * @param again    또 먹을래요. null = 안 고름
 * @param textures 식감 칩(여러 개)
 * @param tastes   맛 칩(여러 개)
 * @param memo     빵별 한 줄
 */
public record BreadTagRatingRequest(
		Boolean again,
		@Size(max = 16) List<TextureChip> textures,
		@Size(max = 10) List<TasteChip> tastes,
		@Size(max = 100) String memo) {
}
