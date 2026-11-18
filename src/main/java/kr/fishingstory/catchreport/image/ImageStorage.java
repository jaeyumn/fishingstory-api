package kr.fishingstory.catchreport.image;

import java.time.Duration;

public interface ImageStorage {
	String uploadUrl(String key, Duration duration);
	String downloadUrl(String key, Duration duration);
	Metadata inspect(String key, long maxBytes, int maxDimension);
	void promote(String sourceKey, String destinationKey, String etag);
	void delete(String key);
	record Metadata(long bytes, String contentType, int width, int height, String etag) { }
}
