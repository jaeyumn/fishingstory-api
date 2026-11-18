package kr.fishingstory.catchreport.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import kr.fishingstory.catchreport.query.application.PublicCatchReportQueryService;
import kr.fishingstory.catchreport.query.dto.PublicCatchReportItem;

@RestController
@Validated
@SecurityRequirement(name = "bearerAuth")
public class PublicCatchReportController {
	private final PublicCatchReportQueryService queries;
	public PublicCatchReportController(PublicCatchReportQueryService queries) { this.queries = queries; }

	@GetMapping("/api/catch-reports")
	@Operation(summary = "공개 조황 목록", description = "정상 회원의 공개 기록을 낚시 날짜·생성 시각·ID 역순으로 조회한다. waterType(ALL/SEA/FRESHWATER), catchStatus(ALL/CAUGHT/NO_CATCH), 양수 speciesId는 AND 필터이며 변경 시 커서를 비운다. 사진·위치는 제공하지 않는다.")
	public ResponseEntity<PublicCatchReportQueryService.Page> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit,
			@RequestParam(required = false) @Size(max = 300) String cursor,
			@RequestParam(defaultValue = "ALL") String waterType,
			@RequestParam(defaultValue = "ALL") String catchStatus,
			@RequestParam(required = false) Long speciesId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(queries.find(AuthenticatedUser.id(jwt), cursor, limit, waterType, catchStatus, speciesId));
	}

	@GetMapping("/api/catch-reports/{reportId}")
	@Operation(summary = "공개 조황 상세", description = "비공개·삭제·비정상 작성자의 기록은 404를 반환한다.")
	public ResponseEntity<PublicCatchReportItem> detail(@AuthenticationPrincipal Jwt jwt,
			@PathVariable long reportId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(queries.findOne(AuthenticatedUser.id(jwt), reportId));
	}
}
