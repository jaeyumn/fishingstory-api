package kr.fishingstory.inquiry.command.domain;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "inquiry_messages", schema = "fishingstory")
public class InquiryMessage {
	@Id @Column(name = "message_id") private Long id;
	@Column(name = "inquiry_id", nullable = false) private long inquiryId;
	@Column(name = "sender_id", nullable = false) private long senderId;
	@Column(name = "sender_role", nullable = false, length = 20) private String senderRole;
	@Column(name = "request_key", nullable = false, length = 32) private String requestKey;
	@Column(nullable = false, columnDefinition = "text") private String content;
	@Column(name = "created_at", nullable = false) private Instant createdAt;
	protected InquiryMessage() { }
	public InquiryMessage(long id, long inquiryId, long senderId, boolean admin, String requestKey, String content, Instant now) {
		this.id = id; this.inquiryId = inquiryId; this.senderId = senderId;
		this.senderRole = admin ? "ADMIN" : "USER"; this.requestKey = requestKey;
		this.content = content; this.createdAt = now;
	}
	public long id() { return id; }
	public String content() { return content; }
	public String senderRole() { return senderRole; }
}
