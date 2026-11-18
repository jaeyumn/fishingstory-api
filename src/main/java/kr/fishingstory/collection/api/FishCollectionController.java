package kr.fishingstory.collection.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.collection.query.application.FishCollectionQueryService;

@Validated
@RestController
@SecurityRequirement(name = "bearerAuth")
public class FishCollectionController {
	private final FishCollectionQueryService service;

	public FishCollectionController(FishCollectionQueryService service) { this.service = service; }

	@Operation(summary = "내 도감", description = "활성 Master 어종과 이미 획득한 비활성 어종을 내 획득 상태와 함께 조회한다.")
	@GetMapping("/api/fish-collection/me")
	public ResponseEntity<FishCollectionQueryService.Result> mine(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|ACQUIRED|UNACQUIRED") String status,
			@RequestParam(defaultValue = "ALL") @Pattern(regexp = "ALL|SEA|FRESHWATER") String waterType,
			@RequestParam(required = false) @Size(max = 50) String query) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.find(AuthenticatedUser.id(jwt), status, waterType, query));
	}

	@Operation(summary = "내 도감 어종 상세", description = "어종의 내 획득 기록과 해당 어종을 포함한 최근 조황 20건을 조회한다.")
	@GetMapping("/api/fish-collection/me/{speciesId}")
	public ResponseEntity<FishCollectionQueryService.Detail> detail(@AuthenticationPrincipal Jwt jwt,
			@PathVariable long speciesId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(service.detail(AuthenticatedUser.id(jwt), speciesId));
	}
}
