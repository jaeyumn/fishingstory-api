package kr.fishingstory.catchreport.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.ObjectMapper;
import kr.fishingstory.auth.infrastructure.jwt.AccessTokenIssuer;
import kr.fishingstory.catchreport.image.ImageStorage;
import kr.fishingstory.mail.EmailSender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "FISHING_KMA_SERVICE_KEY=", "FISHING_TIDE_SERVICE_KEY=",
        "app.image.enabled=false", "app.magazine.images.enabled=false"})
@ActiveProfiles("test")
@Import({CatchReportApiTests.DatabaseConfiguration.class, SpeciesCatalogHttpTests.ValkeyConfiguration.class})
class SpeciesCatalogHttpTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenIssuer tokens;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @MockitoBean EmailSender mail;
    @MockitoBean ImageStorage images;
    @Value("${local.server.port}") int port;
    private int requests;

    @Test void verifiesCatalogAndCatchLifecycleOverRealLoopbackHttp() throws Exception {
        insertMember(81901, "CatalogHTTP", "catalog-http@example.test");
        insertMember(81902, "OtherHTTP", "other-http@example.test");
        String credential = tokens.issue(81901, "USER", UUID.randomUUID().toString()).value();
        String other = tokens.issue(81902, "USER", UUID.randomUUID().toString()).value();
        String caught = """
            {"catchStatus":"CAUGHT","waterType":"SEA","fishingDate":"2026-09-20",
             "requestKey":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
             "species":[{"speciesId":1003,"maxLengthCm":32.5,"maxWeightG":450.5},
                        {"customName":"HTTP 직접 입력","maxWeightG":125}]}
            """;
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var health = exchange(client, "GET", "/actuator/health", null, null, 200);
            assertThat(json.readTree(health.body()).path("status").asString()).isEqualTo("UP");
            exchange(client, "GET", "/api/fish-species/catalog", null, null, 401);
            var catalogResponse = exchange(client, "GET", "/api/fish-species/catalog", credential, null, 200);
            assertThat(catalogResponse.headers().firstValue("Cache-Control")).contains("no-store");
            var catalog = json.readTree(catalogResponse.body());
            assertThat(catalog.path("items").size()).isEqualTo(7);
            String version = catalog.path("version").asString();
            assertThat(version).matches("[a-f0-9]{64}");
            assertThat(catalog.path("items").toString()).contains("우럭").doesNotContain("reportCount");
            var common = json.readTree(exchange(client, "GET", "/api/fish-species/1003", credential, null, 200).body());
            assertThat(common.path("regulations").isEmpty()).isTrue();
            assertThat(common.path("species").path("familyName").isNull()
                    || common.path("species").path("familyName").isMissingNode()).isTrue();

            var createdResponse = exchange(client, "POST", "/api/catch-reports", credential, caught, 201);
            assertThat(createdResponse.headers().firstValue("Cache-Control")).contains("no-store");
            var created = json.readTree(createdResponse.body());
            long reportId = created.path("reportId").asLong();
            String path = "/api/catch-reports/me/" + reportId;
            assertThat(created.path("collectionEvents").size()).isEqualTo(1);
            var replay = json.readTree(exchange(client, "POST", "/api/catch-reports", credential, caught, 201).body());
            assertThat(replay.path("reportId").asLong()).isEqualTo(reportId);
            assertThat(replay.path("collectionEvents").isEmpty()).isTrue();
            exchange(client, "POST", "/api/catch-reports", credential, caught.replace("450.5", "451.5"), 409);

            var report = json.readTree(exchange(client, "GET", path, credential, null, 200).body());
            assertThat(report.path("species").size()).isEqualTo(2);
            assertThat(report.path("species").get(1).path("name").asString()).isEqualTo("HTTP 직접 입력");
            assertThat(report.path("species").get(1).path("speciesId").isNull()
                    || report.path("species").get(1).path("speciesId").isMissingNode()).isTrue();
            assertThat(report.path("species").get(1).path("maxWeightG").asDouble()).isEqualTo(125);
            var collection = json.readTree(exchange(client, "GET", "/api/fish-collection/me/1003", credential, null, 200).body());
            assertThat(collection.path("reportCount").asLong()).isEqualTo(1);
            assertThat(collection.path("maxWeightG").asDouble()).isEqualTo(450.5);
            assertThat(collection.path("maxLengthCm").asDouble()).isEqualTo(32.5);
            exchange(client, "GET", path, other, null, 404);
            exchange(client, "PUT", path, other, caught, 404);
            exchange(client, "DELETE", path, other, null, 404);

            String customOnly = """
                {"catchStatus":"CAUGHT","waterType":"SEA","fishingDate":"2026-09-20",
                 "species":[{"customName":"HTTP 직접 입력","maxWeightG":20}]}
                """;
            exchange(client, "PUT", path, credential, customOnly, 204);
            var removed = json.readTree(exchange(client, "GET", "/api/fish-collection/me/1003", credential, null, 200).body());
            assertThat(removed.path("acquired").asBoolean()).isFalse();
            assertThat(removed.path("reportCount").asLong()).isZero();
            assertThat(removed.path("reports").isEmpty()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=81901", Integer.class)).isZero();
            exchange(client, "PUT", path, credential, caught, 204);
            var restored = json.readTree(exchange(client, "GET", "/api/fish-collection/me/1003", credential, null, 200).body());
            assertThat(restored.path("reportCount").asLong()).isEqualTo(1);
            assertThat(restored.path("maxWeightG").asDouble()).isEqualTo(450.5);
            exchange(client, "DELETE", path, credential, null, 204);
            exchange(client, "GET", path, credential, null, 404);
            var deleted = json.readTree(exchange(client, "GET", "/api/fish-collection/me/1003", credential, null, 200).body());
            assertThat(deleted.path("acquired").asBoolean()).isFalse();
            assertThat(deleted.path("reportCount").asLong()).isZero();

            var noCatch = json.readTree(exchange(client, "POST", "/api/catch-reports", credential, """
                {"catchStatus":"NO_CATCH","waterType":"SEA","fishingDate":"2026-09-20"}
                """, 201).body());
            assertThat(noCatch.path("collectionEvents").isEmpty()).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.user_fish_collection WHERE user_id=81901", Integer.class)).isZero();
            var unchanged = json.readTree(exchange(client, "GET", "/api/fish-species/catalog", credential, null, 200).body());
            assertThat(unchanged.path("version").asString()).isEqualTo(version);
            assertThat(unchanged.path("items").size()).isEqualTo(7);
            jdbc.update("UPDATE fishingstory.users SET status='WITHDRAW_PENDING',withdrawal_requested_at=now() WHERE user_id=81901");
            exchange(client, "GET", "/api/fish-species/catalog", credential, null, 403);
            exchange(client, "GET", "/api/fish-species/1003", credential, null, 403);
        }
        System.out.println("Catalog/catch loopback HTTP: " + requests
                + " requests passed (health, authentication, catalog, custom/weight, replay/conflict, ownership, projection rebuild, NO_CATCH, inactive member).");
    }

    @Test void verifiesWithdrawalAndPhotoReservationRecoveryOverRealLoopbackHttp() throws Exception {
        String email = "recovery-http@example.test";
        String password = "test-password-42";
        insertMember(81903, "RecoveryHTTP", email);
        jdbc.update("UPDATE fishingstory.user_auth SET password_hash=? WHERE user_id=81903", passwords.encode(password));
        when(mail.send(any(EmailSender.Message.class), any(BooleanSupplier.class)))
                .thenReturn(new EmailSender.Result(EmailSender.Status.ACCEPTED, "test-mail"));
        when(images.uploadUrl(anyString(), any())).thenReturn("https://r2.example.test/upload");
        when(images.downloadUrl(anyString(), any())).thenReturn("https://r2.example.test/read");
        when(images.inspect(anyString(), anyLong(), anyInt()))
                .thenReturn(new ImageStorage.Metadata(1000, "image/jpeg", 600, 400, "etag"));

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            String loginBody = json.writeValueAsString(Map.of("email", email, "password", password));
            var first = json.readTree(exchange(client, "POST", "/api/auth/login/email", null, loginBody, 200).body());
            String old = first.path("accessToken").asString();
            String other = json.readTree(exchange(client, "POST", "/api/auth/login/email", null, loginBody, 200).body())
                    .path("accessToken").asString();
            exchange(client, "POST", "/api/users/me/withdrawal", old, "{\"confirmed\":true}", 200);
            exchange(client, "GET", "/api/users/me", other, null, 403);

            String cancel = "/api/auth/withdrawal-cancel";
            exchange(client, "POST", cancel + "/email-code", null, json.writeValueAsString(Map.of("email", email)), 200);
            var message = ArgumentCaptor.forClass(EmailSender.Message.class);
            verify(mail).send(message.capture(), any(BooleanSupplier.class));
            String code = message.getValue().text().replaceAll("(?s).*인증코드는 ([0-9]{6})입니다.*", "$1");
            String grant = json.readTree(exchange(client, "POST", cancel + "/email-code/verify", null,
                    json.writeValueAsString(Map.of("email", email, "code", code)), 200).body()).path("cancellationToken").asString();
            exchange(client, "POST", cancel, null, json.writeValueAsString(Map.of("email", email, "cancellationToken", grant)), 204);
            exchange(client, "GET", "/api/users/me", old, null, 401);
            exchange(client, "GET", "/api/users/me", other, null, 401);
            exchange(client, "POST", "/api/auth/refresh", null,
                    json.writeValueAsString(Map.of("refreshToken", first.path("refreshToken").asString())), 401);

            var login = json.readTree(exchange(client, "POST", "/api/auth/login/email", null, loginBody, 200).body());
            exchange(client, "GET", "/api/users/me", login.path("accessToken").asString(), null, 200);
            var refreshed = json.readTree(exchange(client, "POST", "/api/auth/refresh", null,
                    json.writeValueAsString(Map.of("refreshToken", login.path("refreshToken").asString())), 200).body());
            String access = refreshed.path("accessToken").asString();
            long reportId = json.readTree(exchange(client, "POST", "/api/catch-reports", access, """
                    {"catchStatus":"NO_CATCH","waterType":"SEA","fishingDate":"2026-09-20"}
                    """, 201).body()).path("reportId").asLong();

            String gallery = "/api/catch-reports/me/" + reportId + "/images";
            String key = json.writeValueAsString(Map.of("requestKey", "b".repeat(32)));
            var reserved = json.readTree(exchange(client, "POST", gallery, access, key, 201).body());
            var replay = json.readTree(exchange(client, "POST", gallery, access, key, 201).body());
            long imageId = reserved.path("imageId").asLong();
            assertThat(imageId).isPositive();
            assertThat(replay.path("imageId").asLong()).isEqualTo(imageId);
            assertThat(replay.path("uploadUrlExpiresAt").asString()).isEqualTo(reserved.path("uploadUrlExpiresAt").asString());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fishingstory.catch_report_images WHERE report_id=?", Integer.class, reportId)).isEqualTo(1);
            jdbc.update("UPDATE fishingstory.catch_report_images SET created_at=now()-interval '11 minutes' WHERE image_id=?", imageId);
            var expired = json.readTree(exchange(client, "POST", gallery, access, key, 201).body());
            assertThat(expired.path("imageId").asLong()).isEqualTo(imageId);
            assertThat(expired.path("masterUploadUrl").isNull() || expired.path("masterUploadUrl").isMissingNode()).isTrue();
            exchange(client, "DELETE", gallery + "/" + imageId + "?pendingOnly=true", access, null, 204);
            exchange(client, "POST", gallery, access, key, 409);

            String nextKey = json.writeValueAsString(Map.of("requestKey", "c".repeat(32)));
            long nextId = json.readTree(exchange(client, "POST", gallery, access, nextKey, 201).body()).path("imageId").asLong();
            assertThat(nextId).isPositive().isNotEqualTo(imageId);
            exchange(client, "POST", gallery + "/" + nextId + "/complete", access, null, 204);
            exchange(client, "DELETE", gallery + "/" + nextId + "?pendingOnly=true", access, null, 409);
            assertThat(json.readTree(exchange(client, "GET", gallery, access, null, 200).body()).path("items").size()).isEqualTo(1);
        }
        System.out.println("Withdrawal/photo recovery loopback HTTP: " + requests + " requests passed.");
    }

    private HttpResponse<String> exchange(HttpClient client, String method, String path, String token,
            String body, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15)).method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) request.header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        requests++;
        assertThat(response.statusCode()).as("HTTP %s %s", method, path).isEqualTo(expectedStatus);
        return response;
    }

    private void insertMember(long userId, String nickname, String email) {
        jdbc.update("INSERT INTO fishingstory.users (user_id,nickname,nickname_key,age_confirmed_at) VALUES (?,?,lower(?),now())", userId, nickname, nickname);
        jdbc.update("INSERT INTO fishingstory.user_auth (user_auth_id,user_id,provider,email,password_hash,email_verified_at) VALUES (?,?,'EMAIL',?,'test-hash',now())", userId + 100, userId, email);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ValkeyConfiguration {
        @Bean @ServiceConnection(name = "redis") GenericContainer<?> valkey() {
            return new GenericContainer<>("valkey/valkey:8.1.10").withExposedPorts(6379);
        }
    }
}
