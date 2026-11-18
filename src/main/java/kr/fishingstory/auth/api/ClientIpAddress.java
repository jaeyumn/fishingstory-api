package kr.fishingstory.auth.api;

import java.net.InetAddress;
import java.net.UnknownHostException;
import kr.fishingstory.common.error.ApiException;
import kr.fishingstory.common.error.ErrorCode;

final class ClientIpAddress {
	private ClientIpAddress() { }
	static String canonical(String address) {
		if (address == null || !address.matches("[0-9a-fA-F:.]+")) throw new ApiException(ErrorCode.VALIDATION_ERROR);
		try { return InetAddress.getByName(address).getHostAddress(); }
		catch (UnknownHostException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
	}
}
