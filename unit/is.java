package com.example.consent.wiremock;

import com.example.consent.dto.IdentityResponse;
import com.example.consent.exception.OBException;
import com.example.consent.feign.IdentityClient;
import com.example.consent.properties.IdentityServerProperties;
import com.example.consent.service.IdentityClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import feign.Feign;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.junit.jupiter.api.*;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityClientServiceWireMockTest {

    private static WireMockServer wireMock;
    private IdentityClientService service;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();

        IdentityClient client = Feign.builder()
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .errorDecoder(new IdentityCarbonErrorDecoder(new ObjectMapper()))
                .target(IdentityClient.class, "http://localhost:" + wireMock.port());

        IdentityServerProperties props = new IdentityServerProperties();
        props.setAuth(new IdentityServerProperties.Auth());
        props.getAuth().setUsername("test");
        props.getAuth().setPassword("test");

        service = new IdentityClientService(client, props);
    }

    @Test
    @DisplayName("Успешный поиск authorized app по совпадению имени")
    void getAuthorizedApps_success() {
        String userId = "user-123";
        Map<String, String> claims = Map.of(
                "SUBSCRIBER", "sub-001",
                "APPLICATION_UUID", "app-uuid-123",
                "KEY_TYPE", "API_KEY"
        );

        // Ожидаемое имя: sub-001_app-uuid-123_API_KEY
        wireMock.stubFor(get(urlPathEqualTo("/authorized-apps/" + userId))
                .withHeader("Authorization", containing("Basic"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"name\":\"sub-001_app-uuid-123_API_KEY\",\"clientId\":\"client-abc-123\"}]")));

        String result = service.getAuthorizedApps(userId, claims);

        assertThat(result).isEqualTo("client-abc-123");
    }

    @Test
    @DisplayName("Client not found -> OBException 400")
    void getAuthorizedApps_clientNotFound_throwsOBException() {
        String userId = "user-123";
        Map<String, String> claims = Map.of(
                "SUBSCRIBER", "sub-001",
                "APPLICATION_UUID", "app-uuid-123",
                "KEY_TYPE", "API_KEY"
        );

        wireMock.stubFor(get(urlPathEqualTo("/authorized-apps/" + userId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"name\":\"other-name\",\"clientId\":\"other-id\"}]")));

        assertThatThrownBy(() -> service.getAuthorizedApps(userId, claims))
                .isInstanceOf(OBException.class)
                .satisfies(ex -> {
                    OBException ob = (OBException) ex;
                    assertThat(ob.getStatusCode()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("401 Unauthorized -> RetryableException (для @Retryable)")
    void getAuthorizedApps_401_returnsRetryableException() {
        wireMock.stubFor(get(urlPathMatching("/authorized-apps/.*"))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> service.getAuthorizedApps("user-123", Map.of()))
                .isInstanceOf(RetryableException.class);
    }

    @Test
    @DisplayName("500 с телом ошибки -> Wso2Exception")
    void getAuthorizedApps_500_withBody_returnsWso2Exception() {
        wireMock.stubFor(get(urlPathMatching("/authorized-apps/.*"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"IS error\",\"description\":\"DB connection lost\"}")));

        assertThatThrownBy(() -> service.getAuthorizedApps("user-123", Map.of()))
                .isInstanceOf(Wso2Exception.class)
                .hasMessageContaining("DB connection lost");
    }

    @Test
    @DisplayName("500 с пустым телом -> OBException 500")
    void getAuthorizedApps_500_emptyBody_returnsOBException() {
        wireMock.stubFor(get(urlPathMatching("/authorized-apps/.*"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.getAuthorizedApps("user-123", Map.of()))
                .isInstanceOf(OBException.class);
    }
}


//testImplementation 'org.springframework.boot:spring-boot-starter-test'
//        testImplementation 'com.github.tomakehurst:wiremock-jre8:2.35.0'
//        testImplementation 'io.github.openfeign:feign-jackson:12.3'