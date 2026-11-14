package uy.edu.prisma.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import uy.edu.prisma.domain.exception.VpnRequiredException;

class VpnAccessGuardTest {

  private final VpnAccessGuard guard = new VpnAccessGuard(List.of("127.0.0.0/8", " 10.8.0.0/24 "));

  @Test
  void allowsAddressesInsideTheConfiguredNetworks() {
    assertTrue(guard.isAllowed("127.0.0.1"));
    assertTrue(guard.isAllowed("10.8.0.15"));
  }

  @Test
  void deniesAddressesOutsideTheNetworks() {
    assertFalse(guard.isAllowed("10.8.1.15"));
    assertFalse(guard.isAllowed("181.20.4.9"));
  }

  @Test
  void deniesMissingOrMalformedAddresses() {
    assertFalse(guard.isAllowed(null));
    assertFalse(guard.isAllowed(" "));
    assertFalse(guard.isAllowed("no-es-una-ip"));
  }

  @Test
  void withoutNetworksNobodyIsAllowed() {
    assertFalse(new VpnAccessGuard(List.of()).isAllowed("127.0.0.1"));
  }

  @Test
  void requireThrowsVpnRequiredOutsideTheNetworks() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("181.20.4.9");

    VpnRequiredException ex = assertThrows(VpnRequiredException.class, () -> guard.require(request));
    assertEquals(VpnAccessGuard.DENIED_MESSAGE, ex.getMessage());
  }

  @Test
  void requirePassesInsideTheNetworks() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.8.0.2");

    assertDoesNotThrow(() -> guard.require(request));
  }
}
