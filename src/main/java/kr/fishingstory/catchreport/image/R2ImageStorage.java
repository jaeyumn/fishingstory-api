package kr.fishingstory.catchreport.image;

import java.net.URI;
import java.time.Duration;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Component
@ConditionalOnProperty(name = "app.image.enabled", havingValue = "true")
public class R2ImageStorage implements ImageStorage {
	// Completion uses six storage calls: bound their combined network wait to 24 seconds.
	private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(4);
	private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);
	private final String bucket;
	private final S3Client client;
	private final S3Presigner presigner;

	public R2ImageStorage(@Value("${R2_ENDPOINT}") String endpoint,
			@Value("${R2_ACCESS_KEY_ID}") String accessKey,
			@Value("${R2_SECRET_ACCESS_KEY}") String secret,
			@Value("${R2_BUCKET_NAME}") String bucket) {
		if (endpoint.isBlank() || accessKey.isBlank() || secret.isBlank() || bucket.isBlank()) {
			throw new IllegalArgumentException("R2 configuration is incomplete");
		}
		URI uri = URI.create(endpoint);
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
				|| !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
			throw new IllegalArgumentException("R2 endpoint must be an HTTPS origin");
		}
		this.bucket = bucket;
		var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secret));
		var config = S3Configuration.builder().pathStyleAccessEnabled(true)
				.chunkedEncodingEnabled(false).build();
		this.client = S3Client.builder().endpointOverride(uri)
				.region(Region.of("auto")).credentialsProvider(credentials)
				.serviceConfiguration(config)
				.overrideConfiguration(override -> override.apiCallTimeout(API_CALL_TIMEOUT)
						.apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)).build();
		this.presigner = S3Presigner.builder().endpointOverride(uri)
				.region(Region.of("auto")).credentialsProvider(credentials)
				.serviceConfiguration(config).build();
	}

	@Override public String uploadUrl(String key, Duration duration) {
		var request = PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/jpeg").build();
		return presigner.presignPutObject(PutObjectPresignRequest.builder()
				.signatureDuration(duration).putObjectRequest(request).build()).url().toString();
	}

	@Override public String downloadUrl(String key, Duration duration) {
		var request = GetObjectRequest.builder().bucket(bucket).key(key).build();
		return presigner.presignGetObject(GetObjectPresignRequest.builder()
				.signatureDuration(duration).getObjectRequest(request).build()).url().toString();
	}

	@Override public Metadata inspect(String key, long maxBytes, int maxDimension) {
		var response = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
		if (response.contentLength() <= 0 || response.contentLength() > maxBytes
				|| !"image/jpeg".equals(response.contentType())) {
			throw new IllegalArgumentException("Invalid image metadata");
		}
		var object = client.getObjectAsBytes(GetObjectRequest.builder()
				.bucket(bucket).key(key).range("bytes=0-" + maxBytes).build());
		byte[] bytes = object.asByteArray();
		if (bytes.length != response.contentLength() || hasExif(bytes)) {
			throw new IllegalArgumentException("Invalid image content");
		}
		if (response.eTag() == null || !response.eTag().equals(object.response().eTag())) {
			throw new IllegalArgumentException("Image changed during verification");
		}
		try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) throw new IllegalArgumentException("Invalid JPEG");
			var reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (!"JPEG".equalsIgnoreCase(reader.getFormatName()) || width <= 0 || height <= 0
						|| Math.max(width, height) > maxDimension) {
					throw new IllegalArgumentException("Invalid image dimensions");
				}
				if (reader.read(0) == null) throw new IllegalArgumentException("Invalid JPEG");
				return new Metadata(bytes.length, response.contentType(), width, height, response.eTag());
			} finally {
				reader.dispose();
			}
		} catch (java.io.IOException failure) {
			throw new IllegalArgumentException("Invalid JPEG", failure);
		}
	}

	private boolean hasExif(byte[] bytes) {
		for (int index = 0; index + 9 < bytes.length; index++) {
			if ((bytes[index] & 0xff) == 0xff && (bytes[index + 1] & 0xff) == 0xe1
					&& bytes[index + 4] == 'E' && bytes[index + 5] == 'x'
					&& bytes[index + 6] == 'i' && bytes[index + 7] == 'f'
					&& bytes[index + 8] == 0 && bytes[index + 9] == 0) return true;
		}
		return false;
	}

	@Override public void promote(String sourceKey, String destinationKey, String etag) {
		client.copyObject(CopyObjectRequest.builder().copySource(bucket + "/" + sourceKey)
				.copySourceIfMatch(etag)
				.bucket(bucket).key(destinationKey).build());
	}

	@Override public void delete(String key) {
		client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
	}

	@PreDestroy public void close() {
		presigner.close();
		client.close();
	}
}
