package kr.fishingstory.catchreport.api;

import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.catchreport.image.CatchReportImageService;

@RestController
@RequestMapping("/api/catch-reports/me/{reportId}/images")
@SecurityRequirement(name = "bearerAuth")
public class CatchReportImageController {
	private final CatchReportImageService service;
	public CatchReportImageController(CatchReportImageService service) { this.service = service; }

	@Operation(summary = "비공개 조황 사진 업로드 예약",
			description = "선택 requestKey로 같은 PENDING 예약을 복구한다. 최초 업로드 기한이 지났으면 URL 없이 기존 ID를 반환한다.")
	@PostMapping
	public ResponseEntity<CatchReportImageService.Upload> reserve(@AuthenticationPrincipal Jwt jwt,
			@PathVariable long reportId, @RequestBody(required = false) ReservationRequest request) {
		var result = service.reserve(AuthenticatedUser.id(jwt), reportId, request == null ? null : request.requestKey());
		return ResponseEntity.created(URI.create("/api/catch-reports/me/" + reportId
				+ "/images/" + result.imageId())).cacheControl(CacheControl.noStore()).body(result);
	}

	@Operation(summary = "사진 업로드 완료 확인", description = "본인 사진을 검증하고 완료한다. 이미 READY인 사진의 재요청은 파일을 다시 복사하거나 순서를 바꾸지 않고 204를 반환한다.")
	@PostMapping("/{imageId}/complete")
	public ResponseEntity<Void> complete(@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId,
			@PathVariable long imageId) {
		service.complete(AuthenticatedUser.id(jwt), reportId, imageId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@Operation(summary = "내 조황 사진 목록과 짧은 수명의 조회 URL")
	@GetMapping
	public ResponseEntity<CatchReportImageService.Gallery> list(@AuthenticationPrincipal Jwt jwt,
			@PathVariable long reportId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(AuthenticatedUser.id(jwt), reportId));
	}

	@Operation(summary = "내 조황 사진 제거 또는 업로드 예약 취소",
			description = "본인의 READY 사진 또는 PENDING 예약을 정리 대상으로 바꾸고 첨부 슬롯을 반환한다. 파일 정리 행은 예약 만료까지 보존한다.")
	@DeleteMapping("/{imageId}")
	public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId,
			@PathVariable long imageId, @RequestParam(defaultValue = "false") boolean pendingOnly) {
		service.remove(AuthenticatedUser.id(jwt), reportId, imageId, pendingOnly);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@Operation(summary = "내 조황 사진 순서 변경")
	@PutMapping("/order")
	public ResponseEntity<Void> reorder(@AuthenticationPrincipal Jwt jwt, @PathVariable long reportId,
			@Valid @RequestBody OrderRequest request) {
		service.reorder(AuthenticatedUser.id(jwt), reportId, request.imageIds());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	public record ReservationRequest(String requestKey) { }
	public record OrderRequest(List<Long> imageIds) { }
}
