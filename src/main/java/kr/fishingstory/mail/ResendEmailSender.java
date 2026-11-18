package kr.fishingstory.mail;

import kr.fishingstory.common.error.ErrorCode;

import kr.fishingstory.common.error.ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ResendEmailSender implements EmailSender {
	private final String apiKey;
	private final String from;
	private final HttpClient client;
	private final URI endpoint;
	private final Clock clock;
	private final Sleeper sleeper;
	private final Duration callTimeout;
	private final Duration totalTimeout;
	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired
	public ResendEmailSender(@Value("${RESEND_API_KEY:}") String apiKey,
			@Value("${MAIL_FROM_EMAIL:}") String fromEmail, @Value("${MAIL_FROM_NAME:}") String fromName) {
		this(apiKey, fromEmail, fromName,
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build(),
				URI.create("https://api.resend.com/emails"), Clock.systemUTC(), Thread::sleep,
				Duration.ofSeconds(5), Duration.ofSeconds(20));
	}

	ResendEmailSender(String apiKey, String fromEmail, String fromName, HttpClient client, URI endpoint,
			Clock clock, Sleeper sleeper, Duration callTimeout, Duration totalTimeout) {
		this.apiKey = apiKey;
		// Quote the display name so punctuation cannot alter the mailbox structure.
		this.from = fromEmail.isBlank() || fromName.isBlank() ? "" : "\""
				+ fromName.replace("\\", "\\\\").replace("\"", "\\\"") + "\" <" + fromEmail + ">";
		this.client = client;
		this.endpoint = endpoint;
		this.clock = clock;
		this.sleeper = sleeper;
		this.callTimeout = callTimeout;
		this.totalTimeout = totalTimeout;
	}

	@Override
	public Result send(Message message) {
		return send(message, () -> true);
	}

	@Override
	public void validateConfiguration() {
		if (apiKey.isBlank() || from.isBlank() || from.contains("\r") || from.contains("\n")) {
			throw new ApiException(ErrorCode.EMAIL_SERVICE_UNAVAILABLE);
		}
	}

	@Override
	public Result send(Message message, java.util.function.BooleanSupplier stillCurrent) {
		validateConfiguration();
		if (message.to() == null || message.to().isBlank() || message.subject() == null || message.text() == null
				|| message.expiresAt() == null || message.operationId() == null
				|| !message.operationId().matches("[A-Za-z0-9_-]{1,200}")) {
			throw new IllegalArgumentException("Invalid email message");
		}
		String payload = json.writeValueAsString(Map.of("from", from, "to", List.of(message.to()),
				"subject", message.subject(), "text", message.text()));
		long started = System.nanoTime();
		boolean ambiguous = false;
		for (int attempt = 0; attempt < 3; attempt++) {
			if (!stillCurrent.getAsBoolean()) break;
			long remaining = remainingMillis(message, started);
			if (remaining <= 0) break;
			var request = HttpRequest.newBuilder(endpoint)
					.timeout(Duration.ofMillis(Math.min(callTimeout.toMillis(), remaining)))
					.header("Authorization", "Bearer " + apiKey)
					.header("Content-Type", "application/json")
					.header("Idempotency-Key", "auth-email/" + message.operationId())
					.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
			var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
			long delay = (attempt + 1) * 1000L;
			try {
				var response = future.get(Math.min(callTimeout.toMillis(), remaining), TimeUnit.MILLISECONDS);
				int status = response.statusCode();
				String id = field(response.body(), "id");
				if (status >= 200 && status < 300 && !id.isBlank()) return new Result(Status.ACCEPTED, id);
				String error = field(response.body(), "name");
				boolean retryable = status >= 500 || status == 408 || status == 429
						|| (status == 409 && error.equals("concurrent_idempotent_requests"));
				boolean definiteRejection = status >= 400 && status < 500 && status != 408 && status != 409;
				ambiguous |= !definiteRejection;
				if (!retryable || error.equals("daily_quota_exceeded") || error.equals("monthly_quota_exceeded")) break;
				delay = retryDelay(response.headers().firstValue("Retry-After").orElse(""), delay);
			} catch (TimeoutException exception) {
				future.cancel(true);
				ambiguous = true;
			} catch (ExecutionException exception) {
				future.cancel(true);
				Throwable cause = exception.getCause();
				ambiguous |= !(cause instanceof java.net.ConnectException
						|| cause instanceof java.net.http.HttpConnectTimeoutException);
			} catch (InterruptedException exception) {
				future.cancel(true);
				Thread.currentThread().interrupt();
				return new Result(Status.UNKNOWN, null);
			}
			if (attempt == 2 || delay >= remainingMillis(message, started)) break;
			try { sleeper.sleep(delay); }
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return new Result(ambiguous ? Status.UNKNOWN : Status.REJECTED, null);
	}

	private long remainingMillis(Message message, long started) {
		return Math.min(Duration.between(clock.instant(), message.expiresAt()).toMillis(),
				totalTimeout.toMillis() - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
	}
	private String field(String body, String name) {
		try { return json.readTree(body).path(name).asString(""); }
		catch (RuntimeException exception) { return ""; }
	}
	private long retryDelay(String value, long fallback) {
		if (value.isBlank()) return fallback;
		try { return Math.multiplyExact(Math.max(0, Long.parseLong(value)), 1000L); }
		catch (NumberFormatException exception) {
			try { return Math.max(0, Duration.between(clock.instant(), ZonedDateTime.parse(value,
					DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
			catch (RuntimeException invalidDate) { return fallback; }
		} catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
	}
	@FunctionalInterface
	interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
