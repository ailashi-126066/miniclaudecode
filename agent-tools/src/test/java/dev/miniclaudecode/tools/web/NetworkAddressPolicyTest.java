package dev.miniclaudecode.tools.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF rules for {@code web:fetch}. A PUBLIC verdict means the URL is fetched with no approval at
 * all, so a range that is wrongly classified public is a silent bypass rather than a UI nuisance.
 *
 * <p>{@code Inet6Address.isSiteLocalAddress()} covers only the deprecated {@code fec0::/10}, so
 * unique-local {@code fc00::/7} — which is what real private IPv6 networks and the AWS IPv6
 * metadata endpoint actually use — was previously treated as public, as was {@code 100.64.0.0/10}
 * CGNAT space.
 */
class NetworkAddressPolicyTest {

  @DisplayName("cloud instance metadata endpoints are hard-blocked, never merely approval-gated")
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "169.254.169.254", // AWS / GCP / Azure / OpenStack
        "fd00:ec2::254", // AWS IPv6 IMDS
        "100.100.100.200", // Alibaba Cloud
        "192.0.0.192", // Oracle Cloud
        "::ffff:169.254.169.254" // IPv4-mapped form of the same endpoint
      })
  void metadataEndpointsAreRecognised(String literal) throws Exception {
    assertThat(NetworkAddressPolicy.isMetadata(InetAddress.getByName(literal))).isTrue();
  }

  @DisplayName("private and non-routable ranges never count as public")
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "127.0.0.1",
        "::1",
        "0.0.0.0",
        "0.1.2.3",
        "10.0.0.5",
        "172.16.0.5",
        "192.168.1.5",
        "169.254.1.1",
        "fe80::1",
        "fc00::1",
        "fd12:3456:789a::1",
        "100.64.0.1",
        "100.127.255.254",
        "198.18.0.1",
        "240.0.0.1",
        "224.0.0.1",
        "::ffff:127.0.0.1",
        "::ffff:10.0.0.1"
      })
  void privateRangesAreNotPublic(String literal) throws Exception {
    assertThat(NetworkAddressPolicy.isPublic(InetAddress.getByName(literal))).isFalse();
  }

  @DisplayName("genuinely routable addresses stay reachable without approval")
  @ParameterizedTest(name = "[{index}] {0}")
  @ValueSource(
      strings = {
        "8.8.8.8",
        "1.1.1.1",
        "93.184.216.34",
        "2606:2800:220:1:248:1893:25c8:1946",
        "100.63.255.255", // just below CGNAT
        "100.128.0.1", // just above CGNAT
        "192.0.1.1" // just outside 192.0.0.0/24
      })
  void publicAddressesRemainPublic(String literal) throws Exception {
    InetAddress address = InetAddress.getByName(literal);
    assertThat(NetworkAddressPolicy.isPublic(address)).isTrue();
    assertThat(NetworkAddressPolicy.isMetadata(address)).isFalse();
  }
}
