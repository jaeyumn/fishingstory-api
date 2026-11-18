package kr.fishingstory.magazine.api;

import java.util.UUID;
import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import kr.fishingstory.auth.api.AuthenticatedUser;
import kr.fishingstory.magazine.command.application.MagazineService;
import kr.fishingstory.magazine.query.dto.MagazineView;

@RestController @Validated @SecurityRequirement(name="bearerAuth")
public class MagazineController {
    private final MagazineService service;
    public MagazineController(MagazineService service){this.service=service;}
    @GetMapping("/api/magazines") public ResponseEntity<MagazineView.Page> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(50) int limit){return ok(service.list(AuthenticatedUser.id(jwt),page,limit));}
    @GetMapping("/api/magazines/{id}") public ResponseEntity<MagazineView.Detail> detail(@AuthenticationPrincipal Jwt jwt,@PathVariable @Min(1) long id){return ok(service.detail(AuthenticatedUser.id(jwt),id,false));}
    @GetMapping("/api/admin/magazines/{id}") public ResponseEntity<MagazineView.Detail> adminDetail(@AuthenticationPrincipal Jwt jwt,@PathVariable @Min(1) long id){return ok(service.detail(AuthenticatedUser.id(jwt),id,true));}
    @GetMapping("/api/admin/magazines/by-source/{sourceId}") public ResponseEntity<MagazineView.Detail> bySource(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID sourceId){return ok(service.bySource(AuthenticatedUser.id(jwt),sourceId));}
    @PutMapping("/api/admin/magazines/imports/{sourceId}") public ResponseEntity<MagazineView.Detail> receive(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID sourceId,@RequestBody MagazineService.Import request){return ok(service.receive(AuthenticatedUser.id(jwt),sourceId,request));}
    @PostMapping("/api/admin/magazines/{id}/publication") public ResponseEntity<MagazineView.Detail> publication(@AuthenticationPrincipal Jwt jwt,@PathVariable @Min(1) long id,@Valid @RequestBody Publication request){return ok(service.publication(AuthenticatedUser.id(jwt),id,request.expectedRevision(),request.published(),request.confirmed()));}
    @PutMapping(value="/api/admin/magazine-images/{id}",consumes="image/png") public ResponseEntity<MagazineService.ImageUploaded> upload(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,HttpServletRequest request)throws IOException{return ok(service.upload(AuthenticatedUser.id(jwt),id,request.getInputStream()));}
    @GetMapping("/api/magazines/{id}/images/{imageId}") public ResponseEntity<MagazineService.ImageBody> image(@AuthenticationPrincipal Jwt jwt,@PathVariable @Min(1) long id,@PathVariable UUID imageId){return ok(service.image(AuthenticatedUser.id(jwt),id,imageId));}
    private static <T> ResponseEntity<T> ok(T value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
    public record Publication(@NotNull @Min(1) Long expectedRevision,@NotNull Boolean published,@NotNull Boolean confirmed) { }
}
