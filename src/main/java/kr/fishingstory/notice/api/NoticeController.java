package kr.fishingstory.notice.api;

import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.notice.command.application.NoticeService;
import kr.fishingstory.notice.query.application.NoticeQueryService;
import kr.fishingstory.notice.query.dto.NoticeDetail;

@Validated
@RestController
@SecurityRequirement(name = "bearerAuth")
public class NoticeController {
	private final NoticeService commands;
	private final NoticeQueryService queries;
	public NoticeController(NoticeService commands, NoticeQueryService queries) { this.commands = commands; this.queries = queries; }
	@Operation(summary = "공개 공지 목록", description = "ACTIVE 회원이 상단 고정 우선·등록 ID 최신순으로 조회한다. 페이지는 0부터 시작한다.")
	@GetMapping("/api/notices")
	public ResponseEntity<NoticeQueryService.Page> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(AuthenticatedUser.id(jwt), false, page, limit));
	}
	@Operation(summary = "공개 공지 상세")
	@GetMapping("/api/notices/{noticeId}")
	public ResponseEntity<NoticeDetail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(AuthenticatedUser.id(jwt), false, noticeId));
	}
	@Operation(summary = "관리자 공지 목록", description = "현재 DB의 ACTIVE/ADMIN 회원만 초안을 포함해 조회한다.")
	@GetMapping("/api/admin/notices")
	public ResponseEntity<NoticeQueryService.Page> adminList(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(AuthenticatedUser.id(jwt), true, page, limit));
	}
	@GetMapping("/api/admin/notices/{noticeId}")
	public ResponseEntity<NoticeDetail> adminDetail(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.detail(AuthenticatedUser.id(jwt), true, noticeId));
	}
	@Operation(summary = "관리자 공지 작성")
	@PostMapping("/api/admin/notices")
	public ResponseEntity<Created> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Request request) {
		long id = commands.create(AuthenticatedUser.id(jwt), request.command());
		return ResponseEntity.created(URI.create("/api/admin/notices/" + id)).cacheControl(CacheControl.noStore()).body(new Created(id));
	}
	@Operation(summary = "관리자 공지 수정", description = "제목·Markdown 본문·공개·상단 고정 상태를 함께 교체한다.")
	@PutMapping("/api/admin/notices/{noticeId}")
	public ResponseEntity<Void> update(@AuthenticationPrincipal Jwt jwt, @PathVariable long noticeId,
			@Valid @RequestBody Request request) {
		commands.update(AuthenticatedUser.id(jwt), noticeId, request.command());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
	public record Request(String title, String content, @NotNull Boolean published, @NotNull Boolean pinned) {
		NoticeService.Command command() { return new NoticeService.Command(title, content, published, pinned); }
	}
	public record Created(long noticeId) { }
}
