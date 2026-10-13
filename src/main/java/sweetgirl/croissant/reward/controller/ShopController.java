package sweetgirl.croissant.reward.controller;

import java.util.Arrays;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import sweetgirl.croissant.global.response.ApiResponse;
import sweetgirl.croissant.reward.dto.request.ShopHomePropRequest;
import sweetgirl.croissant.reward.dto.request.ShopLookRequest;
import sweetgirl.croissant.reward.dto.request.ShopPurchaseRequest;
import sweetgirl.croissant.reward.dto.response.MyShopResponse;
import sweetgirl.croissant.reward.dto.response.ShopCatalogResponse;
import sweetgirl.croissant.reward.dto.response.ShopPurchaseResponse;
import sweetgirl.croissant.reward.service.ShopCommandService;
import sweetgirl.croissant.reward.service.ShopQueryService;

@Tag(name = "[Client] 리워드 상점", description = "친구 모습 · 홈 소품")
@RestController
@RequiredArgsConstructor
public class ShopController {

	private final ShopQueryService shopQueryService;
	private final ShopCommandService shopCommandService;

	@Operation(summary = "상점 품목", description = "회원과 무관한 전역 목록. 응답에 실린 ETag 를 다음 요청의 If-None-Match 로 "
		+ "보내면, 품목이 그대로일 때 304 로 답해 본문을 보내지 않는다.")
	@GetMapping("/rewards/shop/items")
	public ResponseEntity<ApiResponse<ShopCatalogResponse>> getItems(
			@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		ShopCatalogResponse catalog = shopQueryService.getCatalog();
		String eTag = "W/\"" + catalog.version() + "\"";
		if (matches(ifNoneMatch, eTag)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
		}
		return ResponseEntity.ok().eTag(eTag).body(ApiResponse.ok(catalog));
	}

	@Operation(summary = "내 상점", description = "산 품목 · 친구별로 입힌 모습 · 홈에 놓은 소품.")
	@GetMapping("/members/me/rewards/shop")
	public ResponseEntity<ApiResponse<MyShopResponse>> getMyShop(@AuthenticationPrincipal Long memberId) {
		return ResponseEntity.ok(ApiResponse.ok(shopQueryService.getMyShop(memberId)));
	}

	@Operation(summary = "구매", description = "가격은 서버가 품목 표의 값으로 뺀다. 모습은 사면 바로 그 친구에게 입혀진다.")
	@PostMapping("/members/me/rewards/shop/purchase")
	public ResponseEntity<ApiResponse<ShopPurchaseResponse>> purchase(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody ShopPurchaseRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(shopCommandService.purchase(memberId, request)));
	}

	@Operation(summary = "친구 모습 바꾸기", description = "가진 모습만 입힐 수 있다. itemCode 가 null 이면 기본 모습이다.")
	@PutMapping("/members/me/rewards/shop/look")
	public ResponseEntity<ApiResponse<Void>> changeLook(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody ShopLookRequest request) {
		shopCommandService.changeLook(memberId, request.characterCode(), request.itemCode());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	@Operation(summary = "홈 소품 바꾸기", description = "가진 소품만 놓을 수 있다. itemCode 가 null 이면 치운다.")
	@PutMapping("/members/me/rewards/shop/home-prop")
	public ResponseEntity<ApiResponse<Void>> changeHomeProp(
			@AuthenticationPrincipal Long memberId,
			@Valid @RequestBody ShopHomePropRequest request) {
		shopCommandService.changeHomeProp(memberId, request.itemCode());
		return ResponseEntity.ok(ApiResponse.ok(null));
	}

	/** If-None-Match 는 값을 여럿 실을 수 있고 {@code *} 는 "무엇이든 있으면"을 뜻한다. */
	private boolean matches(String ifNoneMatch, String eTag) {
		if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
			return false;
		}
		return Arrays.stream(ifNoneMatch.split(","))
			.map(String::trim)
			.anyMatch(candidate -> candidate.equals("*") || candidate.equals(eTag));
	}
}
