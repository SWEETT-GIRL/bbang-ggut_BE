package sweetgirl.croissant.menu.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 공동 가격 — 누구나 고친다. 0원·100만 원 초과는 400. */
public record MenuPriceUpdateRequest(@NotNull @Min(1) @Max(1_000_000) Integer price) {
}
