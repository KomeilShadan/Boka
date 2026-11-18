package dev.komeil.boka;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.apache.coyote.AbstractProtocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles(value = "local", inheritProfiles = false)
class LocalProfileIT extends ApiIntegrationTest {
  @Autowired ServletWebServerApplicationContext context;

  @Value("${management.server.address}")
  InetAddress managementAddress;

  @Test
  void localProfileUsesIpv4LoopbackAndEnablesDemoLoginAndReadiness() throws Exception {
    var server = (TomcatWebServer) context.getWebServer();
    var protocol = (AbstractProtocol<?>) server.getTomcat().getConnector().getProtocolHandler();
    var apiAddress = protocol.getAddress();
    assertThat(apiAddress).isNotNull();
    assertThat(apiAddress.getHostAddress()).isEqualTo("127.0.0.1");
    assertThat(managementAddress).isEqualTo(apiAddress);
    assertThat(call("GET", "/api/v1/status", "admin", null).statusCode()).isEqualTo(200);
    assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
  }
}
