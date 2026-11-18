package kr.fishingstory.mail;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResendEmailSenderTests {
	HttpServer server;
	HttpClient client;
	ExecutorService executor;
	URI endpoint;
	final ConcurrentLinkedQueue<Reply> replies = new ConcurrentLinkedQueue<>();
	final List<String> bodies = new CopyOnWriteArrayList<>();
	final List<String> keys = new CopyOnWriteArrayList<>();
	final List<String> authorizations = new CopyOnWriteArrayList<>();
	final List<Long> waits = new ArrayList<>();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newCachedThreadPool();
		server.setExecutor(executor);
		server.createContext("/emails", exchange -> {
			try (exchange) {
				bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
				keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
				authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
				Reply reply = replies.poll();
				if (reply == null) reply = new Reply(500, "{}", null, 0);
				if (reply.delay() > 0) {
					try { Thread.sleep(reply.delay()); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
				}
				if (reply.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
				byte[] body = reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(reply.status(), body.length);
				exchange.getResponseBody().write(body);
			}
		});
		server.start();
		endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
		client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
	}

	@AfterEach
	void stop() {
		server.stop(0);
		executor.shutdownNow();
		client.close();
	}

	@Test
	void sendsConfiguredSenderAndPreservesPayloadAndKeyAcrossRetries() {
		replies.add(new Reply(503, "{}", null, 0));
		replies.add(new Reply(429, "{\"name\":\"rate_limit_exceeded\"}", null, 0));
		replies.add(new Reply(200, "{\"id\":\"email-123\"}", null, 0));
		var result = sender().send(message(Instant.now().plusSeconds(300)));
		assertThat(result).isEqualTo(new EmailSender.Result(EmailSender.Status.ACCEPTED, "email-123"));
		assertThat(waits).containsExactly(1000L, 2000L);
		assertThat(bodies).hasSize(3).allMatch(b -> b.equals(bodies.getFirst()));
		assertThat(keys).containsOnly("auth-email/operation-123");
		assertThat(authorizations).containsOnly("Bearer test-only-key");
		var json = JsonMapper.builder().build().readTree(bodies.getFirst());
		assertThat(json.path("from").asString()).isEqualTo("\"낚시이야기\" <sender@example.com>");
		assertThat(json.path("to").get(0).asString()).isEqualTo("fisher@example.com");
		assertThat(json.path("text").asString()).isEqualTo("인증코드 123456");
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 422})
	void rejectsPermanentErrorsWithoutRetry(int status) {
		replies.add(new Reply(status, "{\"name\":\"validation_error\"}", null, 0));
		assertThat(sender().send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.REJECTED);
		assertThat(bodies).hasSize(1);
		assertThat(waits).isEmpty();
	}

	@Test
	void retainsAmbiguityWhenLaterAttemptIsRejected() {
		replies.add(new Reply(503, "{}", null, 0));
		replies.add(new Reply(400, "{}", null, 0));
		assertThat(sender().send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.UNKNOWN);
		assertThat(bodies).hasSize(2);
	}

	@Test
	void retriesAtMostThreeTimes() {
		assertThat(sender().send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.UNKNOWN);
		assertThat(bodies).hasSize(3);
	}

	@Test
	void honorsRetryAfterAndStopsWhenItExceedsBudget() {
		replies.add(new Reply(429, "{}", "3", 0));
		replies.add(new Reply(429, "{}", "60", 0));
		assertThat(sender().send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.REJECTED);
		assertThat(waits).containsExactly(3000L);
		assertThat(bodies).hasSize(2);
	}

	@Test
	void doesNotSendExpiredCode() {
		assertThat(sender().send(message(Instant.now().minusSeconds(1))).status()).isEqualTo(EmailSender.Status.REJECTED);
		assertThat(bodies).isEmpty();
	}

	@Test
	void stopsRetryingWhenCodeExpiresDuringWait() {
		replies.add(new Reply(503, "{}", "60", 0));
		assertThat(sender().send(message(Instant.now().plusSeconds(10))).status()).isEqualTo(EmailSender.Status.UNKNOWN);
		assertThat(bodies).hasSize(1);
		assertThat(waits).isEmpty();
	}

	@Test
	void malformedSuccessIsUnknownAndNotResent() {
		replies.add(new Reply(200, "not-json", null, 0));
		assertThat(sender().send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.UNKNOWN);
		assertThat(bodies).hasSize(1);
	}

	@Test
	void conflictingIdempotencyKeyIsNotRetried() {
		replies.add(new Reply(409, "{\"name\":\"invalid_idempotent_request\"}", null, 0));
		assertThat(sender().send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.UNKNOWN);
		assertThat(bodies).hasSize(1);
	}

	@Test
	void timeoutRetainsUnknownOutcome() {
		for (int i = 0; i < 3; i++) replies.add(new Reply(200, "{\"id\":\"late\"}", null, 1000));
		var sender = new ResendEmailSender("test-only-key", "sender@example.com", "낚시이야기", client, endpoint,
				Clock.systemUTC(), waits::add, Duration.ofMillis(100), Duration.ofSeconds(20));
		assertThat(sender.send(message(Instant.now().plusSeconds(300))).status()).isEqualTo(EmailSender.Status.UNKNOWN);
	}

	@Test
	void missingConfigurationFailsWithoutHttpRequest() {
		var sender = new ResendEmailSender("", "", "", client, endpoint, Clock.systemUTC(), waits::add,
				Duration.ofSeconds(5), Duration.ofSeconds(20));
		assertThatThrownBy(() -> sender.send(message(Instant.now().plusSeconds(300))))
				.isInstanceOf(kr.fishingstory.common.error.ApiException.class).hasMessage("EMAIL_SERVICE_UNAVAILABLE");
		assertThat(bodies).isEmpty();
	}

	@Test
	void replacedCodeStopsBeforeFirstHttpCall() {
		assertThat(sender().send(message(Instant.now().plusSeconds(300)), () -> false).status())
				.isEqualTo(EmailSender.Status.REJECTED);
		assertThat(bodies).isEmpty();
	}

	@Test
	void replacementBetweenRetriesKeepsAmbiguousOutcomeAndStopsSending() {
		replies.add(new Reply(503, "{}", null, 0));
		var checks = new java.util.concurrent.atomic.AtomicInteger();
		assertThat(sender().send(message(Instant.now().plusSeconds(300)), () -> checks.getAndIncrement() == 0).status())
				.isEqualTo(EmailSender.Status.UNKNOWN);
		assertThat(bodies).hasSize(1);
	}

	ResendEmailSender sender() {
		return new ResendEmailSender("test-only-key", "sender@example.com", "낚시이야기", client, endpoint,
				Clock.systemUTC(), waits::add, Duration.ofSeconds(5), Duration.ofSeconds(20));
	}
	EmailSender.Message message(Instant expiry) {
		return new EmailSender.Message("fisher@example.com", "이메일 인증", "인증코드 123456", "operation-123", expiry);
	}
	record Reply(int status, String body, String retryAfter, long delay) { }
}
