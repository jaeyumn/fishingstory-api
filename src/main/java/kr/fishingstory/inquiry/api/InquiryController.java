package kr.fishingstory.inquiry.api;

import java.net.URI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.inquiry.command.application.InquiryService;
import kr.fishingstory.inquiry.query.application.InquiryQueryService;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class InquiryController {
	private final InquiryService commands;
	private final InquiryQueryService queries;
	public InquiryController(InquiryService commands, InquiryQueryService queries) { this.commands = commands; this.queries = queries; }
	@GetMapping("/api/inquiries")
	@Operation(summary = "내 문의 목록")
	public ResponseEntity<InquiryQueryService.Page> mine(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "ALL") String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int limit) {
		return ok(queries.list(AuthenticatedUser.id(jwt), false, status, page, limit));
	}
	@GetMapping("/api/admin/inquiries")
	@Operation(summary = "운영자 문의 목록")
	public ResponseEntity<InquiryQueryService.Page> admin(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "ALL") String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int limit) {
		return ok(queries.list(AuthenticatedUser.id(jwt), true, status, page, limit));
	}
	@GetMapping("/api/inquiries/{id}")
	public ResponseEntity<InquiryQueryService.Detail> detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
			@RequestParam(required = false) Long before, @RequestParam(defaultValue = "20") int limit) {
		return ok(queries.detail(AuthenticatedUser.id(jwt), false, id, before, limit));
	}
	@GetMapping("/api/admin/inquiries/{id}")
	public ResponseEntity<InquiryQueryService.Detail> adminDetail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
			@RequestParam(required = false) Long before, @RequestParam(defaultValue = "20") int limit) {
		return ok(queries.detail(AuthenticatedUser.id(jwt), true, id, before, limit));
	}
	@PostMapping("/api/inquiries")
	@Operation(summary = "문의와 첫 메시지 작성", description = "requestKey는 기기에서 생성한 32자리 소문자 hex 값이다. 같은 요청 재시도는 중복 생성하지 않는다.")
	public ResponseEntity<Created> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Creation request) {
		long id = commands.create(AuthenticatedUser.id(jwt), request.title(), new InquiryService.Message(request.requestKey(), request.content()));
		return ResponseEntity.created(URI.create("/api/inquiries/" + id)).cacheControl(CacheControl.noStore()).body(new Created(id));
	}
	@PostMapping("/api/inquiries/{id}/messages")
	public ResponseEntity<MessageCreated> reply(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody Reply request) {
		return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new MessageCreated(commands.reply(AuthenticatedUser.id(jwt), false, id, request.message())));
	}
	@PostMapping("/api/admin/inquiries/{id}/messages")
	public ResponseEntity<MessageCreated> adminReply(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody Reply request) {
		return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(new MessageCreated(commands.reply(AuthenticatedUser.id(jwt), true, id, request.message())));
	}
	@PatchMapping("/api/inquiries/{id}/status")
	@Operation(summary = "본인 문의 종료·재개", description = "종료된 문의에는 메시지를 추가할 수 없다. 재개 후 추가한다.")
	public ResponseEntity<Void> status(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody State request) {
		commands.status(AuthenticatedUser.id(jwt), false, id, request.status()); return empty();
	}
	@PatchMapping("/api/admin/inquiries/{id}/status")
	public ResponseEntity<Void> adminStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody State request) {
		commands.status(AuthenticatedUser.id(jwt), true, id, request.status()); return empty();
	}
	private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
	private ResponseEntity<Void> empty() { return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
	public record Creation(String title, String content, String requestKey) { }
	public record Reply(String content, String requestKey) {
		InquiryService.Message message() { return new InquiryService.Message(requestKey, content); }
	}
	public record State(String status) { }
	public record Created(long inquiryId) { }
	public record MessageCreated(long messageId) { }
}
