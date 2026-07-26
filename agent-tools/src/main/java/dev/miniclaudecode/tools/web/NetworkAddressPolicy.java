package dev.miniclaudecode.tools.web;

import java.net.InetAddress;

/**
 * Decides whether a resolved address may be fetched without approval.
 *
 * <p>Split out of {@code WebFetchTool} so the SSRF rules can be unit tested against raw {@link
 * InetAddress} values without standing up an HTTP server. Every method is a pure function of the
 * address bytes.
 */
final class NetworkAddressPolicy {

  private NetworkAddressPolicy() {}

  /** Instance metadata endpoints across the major clouds, which must never be reachable at all. */
  static boolean isMetadata(InetAddress address) {
    byte[] bytes = unwrapMappedV4(address.getAddress());
    if (bytes.length == 4) {
      int first = bytes[0] & 255;
      int second = bytes[1] & 255;
      int third = bytes[2] & 255;
      int fourth = bytes[3] & 255;
      // AWS / GCP / Azure / OpenStack
      if (first == 169 && second == 254 && third == 169 && fourth == 254) {
        return true;
      }
      // Alibaba Cloud
      if (first == 100 && second == 100 && third == 100 && fourth == 200) {
        return true;
      }
      // Oracle Cloud
      return first == 192 && second == 0 && third == 0 && fourth == 192;
    }

    return bytes.length == 16 && java.util.Arrays.equals(bytes, AWS_IPV6_IMDS);
  }

  /** {@code fd00:ec2::254} — the AWS IPv6 instance metadata endpoint. */
  private static final byte[] AWS_IPV6_IMDS = {
    (byte) 0xfd, 0x00, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02, 0x54
  };

  /**
   * Whether an address is routable on the public internet.
   *
   * <p>{@link InetAddress}'s own predicates are not sufficient. {@code
   * Inet6Address.isSiteLocalAddress()} covers only the deprecated {@code fec0::/10} and not {@code
   * fc00::/7} unique-local addressing, which is what real private IPv6 networks — including the AWS
   * IPv6 metadata endpoint — actually use; and {@code 100.64.0.0/10} carrier-grade NAT space is not
   * site-local either. Both were previously classified public and therefore fetched with no
   * approval.
   */
  static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }

    byte[] bytes = unwrapMappedV4(address.getAddress());
    if (bytes.length == 4) {
      int first = bytes[0] & 255;
      int second = bytes[1] & 255;
      // 0.0.0.0/8 "this network", 100.64.0.0/10 CGNAT, 192.0.0.0/24 IETF protocol assignments,
      // 198.18.0.0/15 benchmarking and 240.0.0.0/4 reserved space are all non-public.
      return first != 0
          && !(first == 100 && second >= 64 && second <= 127)
          && !(first == 192 && second == 0 && (bytes[2] & 255) == 0)
          && !(first == 198 && (second == 18 || second == 19))
          && first < 240;
    }

    // fc00::/7 unique-local. ::/128 and ::1 are already covered by the predicates above.
    return bytes.length != 16 || (bytes[0] & 0xfe) != 0xfc;
  }

  /** Flattens {@code ::ffff:a.b.c.d} so the IPv4 rules apply to IPv4-mapped IPv6 addresses. */
  private static byte[] unwrapMappedV4(byte[] bytes) {
    if (bytes.length != 16) {
      return bytes;
    }
    for (int index = 0; index < 10; index++) {
      if (bytes[index] != 0) {
        return bytes;
      }
    }
    if ((bytes[10] & 255) != 255 || (bytes[11] & 255) != 255) {
      return bytes;
    }
    return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
  }
}
