package sweetgirl.croissant.admin.dto.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record AdminMenuReorderRequest(@NotNull @Valid List<MenuOrder> orders) {

	public record MenuOrder(
			@NotNull Long menuId,
			@NotNull Integer order) {
	}
}
