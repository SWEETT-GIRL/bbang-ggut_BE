package sweetgirl.croissant.store.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "StoreReport", description = "가게 정보 수정 제보 API")
@RestController
@RequestMapping
@RequiredArgsConstructor
public class StoreReportController {

}
