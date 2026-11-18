package kr.fishingstory.magazine.image;

import java.net.URI;
import java.time.Duration;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;

@Component
@ConditionalOnProperty(name="app.magazine.images.enabled",havingValue="true")
public class R2MagazineImageStorage implements MagazineImageStorage {
    private final S3Client client;private final String bucket;
    public R2MagazineImageStorage(@Value("${R2_ENDPOINT}") String endpoint,@Value("${R2_ACCESS_KEY_ID}") String access,
            @Value("${R2_SECRET_ACCESS_KEY}") String secret,@Value("${R2_BUCKET_NAME}") String bucket) {
        if(endpoint.isBlank()||access.isBlank()||secret.isBlank()||bucket.isBlank())throw new IllegalArgumentException("R2 magazine configuration is incomplete");
        URI uri=URI.create(endpoint);
        if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("R2 endpoint must use HTTPS");
        this.bucket=bucket;
        client=S3Client.builder().endpointOverride(uri).region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
            .overrideConfiguration(c->c.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(10))).build();
    }
    @Override public void put(String key,byte[] png){client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("image/png").build(),RequestBody.fromBytes(png));}
    @Override public byte[] get(String key,int maxBytes) {
        try(var stream=client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).range("bytes=0-"+maxBytes).build())) {
            if(stream.response().contentLength()==null||stream.response().contentLength()>maxBytes||!"image/png".equals(stream.response().contentType()))throw new IllegalStateException("Invalid magazine image");
            byte[] bytes=stream.readNBytes(maxBytes+1);if(bytes.length>maxBytes)throw new IllegalStateException("Invalid magazine image");return bytes;
        }catch(java.io.IOException failure){throw new IllegalStateException("Cannot read magazine image");}
    }
    @PreDestroy public void close(){client.close();}
}
