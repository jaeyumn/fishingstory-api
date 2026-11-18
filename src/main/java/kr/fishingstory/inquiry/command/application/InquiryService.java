package kr.fishingstory.inquiry.command.application;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.common.error.*;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.inquiry.command.domain.*;
import kr.fishingstory.inquiry.command.infrastructure.jpa.InquiryStore;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

@Service
public class InquiryService {
	private final InquiryStore inquiries;
	private final UserCommandStore users;
	private final SnowflakeIdGenerator ids;
	public InquiryService(InquiryStore inquiries, UserCommandStore users, SnowflakeIdGenerator ids) {
		this.inquiries = inquiries; this.users = users; this.ids = ids;
	}
	@Transactional
	public long create(long user, String rawTitle, Message message) {
		checkUser(user, false);
		String title = text(rawTitle, 100, true);
		var existing = inquiries.requested(user, message.requestKey());
		if (existing.isPresent()) {
			var first = inquiries.requestedMessage(existing.get().id(), user, message.requestKey()).orElseThrow();
			if (!existing.get().title().equals(title) || !first.content().equals(message.content())) throw new ApiException(ErrorCode.STATE_CONFLICT);
			return existing.get().id();
		}
		Instant now = Instant.now();
		long id = ids.nextId();
		inquiries.insert(new Inquiry(id, user, message.requestKey(), title, now));
		inquiries.insert(new InquiryMessage(ids.nextId(), id, user, false, message.requestKey(), message.content(), now));
		return id;
	}
	@Transactional
	public long reply(long user, boolean admin, long id, Message message) {
		var inquiry = accessible(user, admin, id);
		var existing = inquiries.requestedMessage(id, user, message.requestKey());
		if (existing.isPresent()) {
			if (!existing.get().content().equals(message.content()) || !existing.get().senderRole().equals(admin ? "ADMIN" : "USER")) {
				throw new ApiException(ErrorCode.STATE_CONFLICT);
			}
			return existing.get().id();
		}
		if (!inquiry.open()) throw new ApiException(ErrorCode.STATE_CONFLICT);
		Instant now = Instant.now();
		long messageId = ids.nextId();
		inquiries.insert(new InquiryMessage(messageId, id, user, admin, message.requestKey(), message.content(), now));
		inquiry.touch(now);
		return messageId;
	}
	@Transactional
	public void status(long user, boolean admin, long id, String status) {
		if (!("OPEN".equals(status) || "CLOSED".equals(status))) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		accessible(user, admin, id).changeStatus(status, Instant.now());
	}
	private Inquiry accessible(long user, boolean admin, long id) {
		checkUser(user, admin);
		var inquiry = inquiries.lock(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!admin && inquiry.userId() != user) throw new ApiException(ErrorCode.NOT_FOUND);
		return inquiry;
	}
	private void checkUser(long id, boolean admin) {
		var user = users.lock(id).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status()) || (admin && !"ADMIN".equals(user.role()))) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}
	private static String text(String value, int max, boolean singleLine) {
		if (value == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		value = value.strip();
		if (value.isBlank() || value.codePointCount(0, value.length()) > max || value.indexOf('\0') >= 0
				|| (singleLine && value.codePoints().anyMatch(Character::isISOControl))) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		return value;
	}
	public record Message(String requestKey, String content) {
		public Message {
			if (requestKey == null || !requestKey.matches("[0-9a-f]{32}")) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			content = text(content, 2000, false);
		}
	}
}
