package kr.fishingstory.notice.command.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.notice.command.domain.Notice;
import kr.fishingstory.notice.command.infrastructure.jpa.NoticeStore;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

@Service
public class NoticeService {
	private final UserCommandStore users;
	private final NoticeStore notices;
	private final SnowflakeIdGenerator ids;
	public NoticeService(UserCommandStore users, NoticeStore notices, SnowflakeIdGenerator ids) {
		this.users = users;
		this.notices = notices;
		this.ids = ids;
	}
	@Transactional
	public long create(long userId, Command command) {
		requireAdmin(userId);
		long id = ids.nextId();
		notices.insert(new Notice(id, command.title(), command.content(), command.published(), command.pinned(), Instant.now()));
		return id;
	}
	@Transactional
	public void update(long userId, long id, Command command) {
		requireAdmin(userId);
		var notice = notices.lock(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		notice.change(command.title(), command.content(), command.published(), command.pinned(), Instant.now());
	}
	private void requireAdmin(long id) {
		var user = users.lock(id).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status()) || !"ADMIN".equals(user.role())) {
			throw new ApiException(ErrorCode.ACCESS_DENIED);
		}
	}
	public record Command(String title, String content, boolean published, boolean pinned) {
		public Command {
			title = title == null ? "" : title.strip();
			content = content == null ? "" : content.strip();
			if (title.isBlank() || title.codePointCount(0, title.length()) > 100
					|| title.codePoints().anyMatch(Character::isISOControl)
					|| content.isBlank() || content.codePointCount(0, content.length()) > 20000
					|| content.indexOf('\0') >= 0) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
		}
	}
}
