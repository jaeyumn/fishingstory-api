package kr.fishingstory.terms.application;

import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;
import kr.fishingstory.common.id.SnowflakeIdGenerator;
import kr.fishingstory.terms.domain.Terms;
import kr.fishingstory.terms.infrastructure.TermsCommandStore;
import kr.fishingstory.user.infrastructure.jpa.UserCommandStore;

@Service
public class TermsManagementService {
	private final UserCommandStore users;
	private final TermsCommandStore terms;
	private final SnowflakeIdGenerator ids;
	public TermsManagementService(UserCommandStore users, TermsCommandStore terms, SnowflakeIdGenerator ids) {
		this.users = users; this.terms = terms; this.ids = ids;
	}
	@Transactional
	public long create(long userId, String type, Draft draft) {
		requireAdmin(userId);
		if (!("TERMS_OF_SERVICE".equals(type) || "PRIVACY_POLICY".equals(type))) {
			throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		long id = ids.nextId();
		terms.insert(new Terms(id, type, draft.version(), draft.title(), draft.content(), draft.effectiveAt(), terms.currentTime()));
		flush();
		return id;
	}
	@Transactional
	public void edit(long userId, long id, Draft draft) {
		requireAdmin(userId);
		var policy = terms.lock(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (policy.published()) throw new ApiException(ErrorCode.STATE_CONFLICT);
		policy.edit(draft.version(), draft.title(), draft.content(), draft.effectiveAt(), terms.currentTime());
		flush();
	}
	@Transactional
	public void publish(long userId, long id, boolean immediately, Instant expectedUpdatedAt) {
		requireAdmin(userId);
		var policy = terms.lock(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (policy.published()) return;
		if (!policy.updatedAt().equals(expectedUpdatedAt)) throw new ApiException(ErrorCode.STATE_CONFLICT);
		// Read after both locks and the reviewed-draft check, not at transaction start.
		Instant now = terms.currentTime();
		try { policy.publish(immediately, now); }
		catch (IllegalArgumentException failure) { throw new ApiException(ErrorCode.STATE_CONFLICT); }
		flush();
	}
	private void flush() {
		try { terms.flush(); }
		catch (DataIntegrityViolationException conflict) { throw new ApiException(ErrorCode.STATE_CONFLICT); }
	}
	private void requireAdmin(long id) {
		var user = users.lock(id).orElseThrow(() -> new ApiException(ErrorCode.ACCESS_DENIED));
		if (!"ACTIVE".equals(user.status()) || !"ADMIN".equals(user.role())) throw new ApiException(ErrorCode.ACCESS_DENIED);
	}
	public record Draft(String version, String title, String content, Instant effectiveAt) {
		public Draft {
			version = clean(version, 30, true);
			title = clean(title, 200, true);
			content = clean(content, 100000, false);
			if (effectiveAt == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		}
		private static String clean(String value, int max, boolean singleLine) {
			if (value == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
			value = value.strip();
			if (value.isBlank() || value.codePointCount(0, value.length()) > max
					|| value.indexOf('\0') >= 0 || (singleLine && value.codePoints().anyMatch(Character::isISOControl))) {
				throw new ApiException(ErrorCode.VALIDATION_ERROR);
			}
			return value;
		}
	}
}
