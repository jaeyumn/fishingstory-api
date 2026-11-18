package kr.fishingstory.conditions;

import java.time.LocalDate;
import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class FishingConditionsController {
	private final FishingConditionsAccessMapper access;
	private final PublicForecastClient forecasts;
	public FishingConditionsController(FishingConditionsAccessMapper access, PublicForecastClient forecasts) {
		this.access = access; this.forecasts = forecasts;
	}
	@Operation(summary = "물때날씨 지원 지역과 한국 날짜 범위")
	@GetMapping("/api/fishing-conditions/locations")
	public ResponseEntity<Locations> locations(@AuthenticationPrincipal Jwt jwt) {
		requireActive(jwt);
		var today = forecasts.today();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new Locations(FishingLocations.ALL, today, today.plusDays(2)));
	}
	@Operation(summary = "선택 지역·날짜의 날씨·해상·고저조·조위 곡선과 물때·일출몰", description = "한국 오늘~모레. 외부 자료별 상태와 기준을 제공하고 음력 일·물때·일출몰은 서버에서 계산한다.")
	@GetMapping("/api/fishing-conditions")
	public ResponseEntity<FishingConditions> conditions(@AuthenticationPrincipal Jwt jwt,
			@RequestParam String locationId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		requireActive(jwt);
		var today = forecasts.today();
		if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(2))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(forecasts.get(FishingLocations.find(locationId), date));
	}
	private void requireActive(Jwt jwt) {
		if (!access.active(AuthenticatedUser.id(jwt))) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}
	public record Locations(List<FishingLocations.Location> items, LocalDate today, LocalDate lastDate) { }
}
