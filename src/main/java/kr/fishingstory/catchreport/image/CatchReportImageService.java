package kr.fishingstory.catchreport.image;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.catchreport.command.domain.CatchReportImage;
import kr.fishingstory.catchreport.command.infrastructure.jpa.CatchReportCommandStore;
import kr.fishingstory.catchreport.command.infrastructure.jpa.CatchReportImageStore;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;
import kr.fishingstory.user.query.infrastructure.mybatis.UserQueryMapper;

@Service
public class CatchReportImageService {
	private static final Duration UPLOAD_URL_LIFETIME = Duration.ofMinutes(10);
	private static final Duration PENDING_LIFETIME = Duration.ofHours(1);
	private static final Duration READ_URL_LIFETIME = Duration.ofMinutes(5);
	private final UserCommandStore users;
	private final UserQueryMapper currentUsers;
	private final CatchReportCommandStore reports;
	private final CatchReportImageStore images;
	private final SnowflakeIdGenerator ids;
	private final ObjectProvider<ImageStorage> storageProvider;
	private final TransactionTemplate transaction;

	public CatchReportImageService(UserCommandStore users, UserQueryMapper currentUsers,
			CatchReportCommandStore reports, CatchReportImageStore images, SnowflakeIdGenerator ids,
			ObjectProvider<ImageStorage> storageProvider, PlatformTransactionManager manager) {
		this.users = users;
		this.currentUsers = currentUsers;
		this.reports = reports;
		this.images = images;
		this.ids = ids;
		this.storageProvider = storageProvider;
		this.transaction = new TransactionTemplate(manager);
	}

	public Upload reserve(long userId, long reportId) {
		return reserve(userId, reportId, null);
	}

	public Upload reserve(long userId, long reportId, String requestKey) {
		if (requestKey != null && !requestKey.matches("[0-9a-f]{32}")) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		ImageStorage storage = storage();
		Upload result = transaction.execute(status -> {
			lockReport(userId, reportId);
			if (requestKey != null) {
				var previous = images.findRequested(userId, reportId, requestKey);
				if (previous.isPresent()) {
					var image = previous.get();
					if (!"PENDING".equals(image.status())) throw new ApiException(ErrorCode.STATE_CONFLICT);
					return upload(image, storage);
				}
			}
			Instant now = Instant.now();
			if (images.activeCount(reportId, now) >= 10) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			long imageId = ids.nextId();
			var image = new CatchReportImage(imageId, reportId, userId, requestKey,
					finalKey(imageId, "master"), finalKey(imageId, "thumbnail"), now, now.plus(PENDING_LIFETIME));
			images.insert(image);
			images.flush();
			return upload(image, storage);
		});
		if (result == null) throw new IllegalStateException("Image reservation returned no result");
		return result;
	}

	private Upload upload(CatchReportImage image, ImageStorage storage) {
		Instant deadline = image.createdAt().truncatedTo(ChronoUnit.SECONDS).plus(UPLOAD_URL_LIFETIME);
		long seconds = Duration.between(Instant.now(), deadline).getSeconds();
		if (seconds < 1) {
			// The caller can still recover the ID to cancel this pending reservation.
			return new Upload(image.id(), null, null, deadline);
		}
		try {
			var remaining = Duration.ofSeconds(seconds);
			return new Upload(image.id(),
					storage.uploadUrl(pendingKey(image.id(), "master"), remaining),
					storage.uploadUrl(pendingKey(image.id(), "thumbnail"), remaining), deadline);
		} catch (RuntimeException unavailable) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}

	public void complete(long userId, long reportId, long imageId) {
		ImageStorage storage = storage();
		transaction.executeWithoutResult(status -> {
			lockReport(userId, reportId);
			var image = images.find(userId, reportId, imageId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			if ("READY".equals(image.status())) return;
			Instant now = Instant.now();
			if (!"PENDING".equals(image.status()) || !now.isBefore(image.expiresAt())) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			try {
				var master = storage.inspect(pendingKey(imageId, "master"), 8_000_000, 2560);
				var thumbnail = storage.inspect(pendingKey(imageId, "thumbnail"), 1_000_000, 720);
				storage.promote(pendingKey(imageId, "master"), image.masterKey(), master.etag());
				storage.promote(pendingKey(imageId, "thumbnail"), image.thumbnailKey(), thumbnail.etag());
				// Keep temporary objects until expiry so a failed DB commit can be retried.
			} catch (IllegalArgumentException invalid) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			} catch (RuntimeException unavailable) {
				throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
			}
			int order = images.list(reportId).size() + 1;
			if (order > 10) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			image.ready(order, now);
			images.flush();
		});
	}

	@Transactional(readOnly = true)
	public Gallery list(long userId, long reportId) {
		ImageStorage storage = storage();
		if (currentUsers.findCurrentEmailUser(userId).isEmpty()
				|| reports.findMine(userId, reportId).isEmpty()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		try {
			List<Item> items = images.list(reportId).stream().map(image -> new Item(
					image.id(), image.displayOrder(),
					storage.downloadUrl(image.masterKey(), READ_URL_LIFETIME),
					storage.downloadUrl(image.thumbnailKey(), READ_URL_LIFETIME))).toList();
			return new Gallery(items);
		} catch (RuntimeException unavailable) {
			throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		}
	}

	public void remove(long userId, long reportId, long imageId, boolean pendingOnly) {
		transaction.executeWithoutResult(status -> {
			lockReport(userId, reportId);
			var image = images.find(userId, reportId, imageId)
					.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			if (pendingOnly && "READY".equals(image.status())) throw new ApiException(ErrorCode.STATE_CONFLICT);
			if (!("READY".equals(image.status()) || "PENDING".equals(image.status()))) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			Instant now = Instant.now();
			image.markForDelete(now);
			images.flush();
			List<CatchReportImage> remaining = images.list(reportId);
			for (int index = 0; index < remaining.size(); index++) remaining.get(index).reorder(index + 1, now);
			images.flush();
		});
	}

	public void reorder(long userId, long reportId, List<Long> imageIds) {
		if (imageIds == null || imageIds.size() > 10
				|| imageIds.stream().anyMatch(id -> id == null || id <= 0)
				|| new HashSet<>(imageIds).size() != imageIds.size()) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		transaction.executeWithoutResult(status -> {
			lockReport(userId, reportId);
			List<CatchReportImage> ready = images.list(reportId);
			if (ready.size() != imageIds.size()
					|| !new HashSet<>(ready.stream().map(CatchReportImage::id).toList())
							.equals(new HashSet<>(imageIds))) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			Instant now = Instant.now();
			for (int index = 0; index < imageIds.size(); index++) {
				long id = imageIds.get(index);
				int order = index + 1;
				ready.stream().filter(image -> image.id() == id).findFirst().orElseThrow().reorder(order, now);
			}
			images.flush();
		});
	}

	private void lockReport(long userId, long reportId) {
		var user = users.lock(userId).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status())) throw new ApiException(ErrorCode.ACCESS_DENIED);
		if (reports.findMine(userId, reportId).isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND);
	}

	private ImageStorage storage() {
		ImageStorage storage = storageProvider.getIfAvailable();
		if (storage == null) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE);
		return storage;
	}

	public static String pendingKey(long imageId, String variant) {
		return "pending/catch-report-images/" + imageId + "/" + variant + ".jpg";
	}
	private static String finalKey(long imageId, String variant) {
		return "catch-report-images/" + imageId + "/" + variant + ".jpg";
	}

	public record Upload(long imageId, String masterUploadUrl, String thumbnailUploadUrl,
			Instant uploadUrlExpiresAt) { }
	public record Item(long imageId, int displayOrder, String masterUrl, String thumbnailUrl) { }
	public record Gallery(List<Item> items) { }
}
