package kr.fishingstory.catchreport.api;

import java.util.List;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.catchreport.query.application.FishSpeciesQueryService;
import kr.fishingstory.catchreport.query.dto.FishSpeciesItem;

@Validated
@RestController
@SecurityRequirement(name = "bearerAuth")
public class FishSpeciesController {
	private final FishSpeciesQueryService service;
	public FishSpeciesController(FishSpeciesQueryService service) { this.service = service; }

	@Operation(summary = "어종 목록", description = "수역에 맞는 활성 마스터 어종을 표시 순서로 조회한다. 대표 국명과 등록된 별칭을 검색한다.")
	@GetMapping("/api/fish-species")
	public ResponseEntity<Response> find(@AuthenticationPrincipal Jwt jwt,
			@RequestParam @Pattern(regexp = "SEA|FRESHWATER") String waterType,
			@RequestParam(required = false) @Size(max = 50) String query) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new Response(service.find(AuthenticatedUser.id(jwt), waterType, query)));
	}

	public record Response(List<FishSpeciesItem> items) { }
}
