package kr.fishingstory.catchreport.image;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.fishingstory.catchreport.command.infrastructure.jpa.CatchReportImageStore;

@Component
@EnableScheduling
public class CatchReportImageCleanup {
	private static final Logger log = LoggerFactory.getLogger(CatchReportImageCleanup.class);
	private final CatchReportImageStore images;
	private final ObjectProvider<ImageStorage> storageProvider;
	private final TransactionTemplate transaction;

	public CatchReportImageCleanup(CatchReportImageStore images,
			ObjectProvider<ImageStorage> storageProvider, PlatformTransactionManager manager) {
		this.images = images;
		this.storageProvider = storageProvider;
		this.transaction = new TransactionTemplate(manager);
	}

	@Scheduled(fixedDelayString = "PT30M")
	public void run() {
		ImageStorage storage = storageProvider.getIfAvailable();
		if (storage == null) return;
		Instant now = Instant.now();
		for (var candidate : images.cleanupCandidates(now)) {
			long imageId = candidate.id();
			try {
				transaction.executeWithoutResult(status -> images.findById(imageId).ifPresent(image -> {
					boolean expired = !Instant.now().isBefore(image.expiresAt());
					// A still-valid upload URL must not outlive its cleanup record.
					boolean remove = expired && ("DELETE_PENDING".equals(image.status())
							|| "PENDING".equals(image.status()));
					if (!remove && !("READY".equals(image.status()) && expired && !image.temporaryCleaned())) return;
					storage.delete(CatchReportImageService.pendingKey(imageId, "master"));
					storage.delete(CatchReportImageService.pendingKey(imageId, "thumbnail"));
					if (remove) {
						storage.delete(image.masterKey());
						storage.delete(image.thumbnailKey());
						images.delete(image);
					} else image.markTemporaryCleaned();
				}));
			} catch (RuntimeException failure) {
				log.warn("Catch report image cleanup will retry; imageId={}", imageId);
			}
		}
	}
}
