package kr.fishingstory.auth.application;

public final class EmailAddressNormalizer {
	private EmailAddressNormalizer() { }
	public static String normalize(String email) {
		if (email == null) return null;
		String stripped = email.strip();
		var normalized = new StringBuilder(stripped.length());
		for (char c : stripped.toCharArray()) normalized.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
		return normalized.toString();
	}
}
