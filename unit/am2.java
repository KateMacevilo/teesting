package com.example.consent.wiremock;

import com.example.consent.dto.AmRevision;
import com.example.consent.dto.AsyncApiDto;
import com.example.consent.feign.ApiManagerClient;
import com.example.consent.mapper.Wso2Mapper;
import com.example.consent.properties.ApiManagerProperties;
import com.example.consent.service.ApiManagerClientService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import feign.Feign;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import org.junit.jupiter.api.*;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiManagerClientServiceWireMockTest {

    private static WireMockServer wireMock;
    private ApiManagerClientService service;

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

        // Создаем Feign клиент вручную, нацеленный на WireMock
        ApiManagerClient client = Feign.builder()
                .encoder(new JacksonEncoder())
                .decoder(new JacksonDecoder())
                .errorDecoder(new ApiManagerErrorDecoder(new ObjectMapper()))
                .target(ApiManagerClient.class, "http://localhost:" + wireMock.port());

        ApiManagerProperties props = new ApiManagerProperties();
        props.setApiId("test-api-123");
        props.setHost("http://localhost:" + wireMock.port());

        service = new ApiManagerClientService(new Wso2Mapper(), client, props);
    }

    @Test
    @DisplayName("getAsyncApi: 200 OK с JSON -> AsyncApiDto")
    void getAsyncApi_success() {
        wireMock.stubFor(get(urlPathEqualTo("/api/test-api-123"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"channels\":{\"topic1\":{}}}")));

        AsyncApiDto result = service.getAsyncApi();

        assertThat(result).isNotNull();
        assertThat(result.getChannels()).containsKey("topic1");
    }

    @Test
    @DisplayName("updateTopics: PUT с телом -> 200 OK")
    void updateTopics_success() {
        wireMock.stubFor(put(urlPathEqualTo("/api/test-api-123/topics"))
                .withRequestBody(containing("topic2"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"channels\":{\"topic2\":{}}}")));

        AsyncApiDto dto = new AsyncApiDto();
        AsyncApiDto result = service.updateTopics(dto);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getRevisions: 200 OK с массивом -> List<AmRevision>")
    void getRevisions_success() {
        wireMock.stubFor(get(urlPathEqualTo("/api/test-api-123/revisions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":\"rev-1\",\"createdTime\":123456},{\"id\":\"rev-2\",\"createdTime\":123457}]")));

        List<AmRevision> result = service.getRevisions();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("rev-1");
    }

    @Test
    @DisplayName("removeRevision: DELETE -> 204")
    void removeRevision_success() {
        wireMock.stubFor(delete(urlPathEqualTo("/api/test-api-123/revisions/rev-1"))
                .willReturn(aResponse().withStatus(204)));

        service.removeRevision("rev-1");

        wireMock.verify(1, deleteRequestedFor(urlPathEqualTo("/api/test-api-123/revisions/rev-1")));
    }

    @Test
    @DisplayName("postRevision: POST -> 201 с телом")
    void postRevision_success() {
        wireMock.stubFor(post(urlPathEqualTo("/api/test-api-123/revisions"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"rev-new\",\"description\":\"\"}")));

        AmRevision input = new AmRevision();
        AmRevision result = service.postRevision(input);

        assertThat(result.getId()).isEqualTo("rev-new");
    }

    @Test
    @DisplayName("deployRevision: POST deploy -> 200")
    void deployRevision_success() {
        wireMock.stubFor(post(urlPathEqualTo("/api/test-api-123/revisions/rev-1/deploy"))
                .willReturn(aResponse().withStatus(200)));

        service.deployRevision("rev-1");

        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/api/test-api-123/revisions/rev-1/deploy")));
    }

    @Test
    @DisplayName("401 Unauthorized -> RetryableException (для @Retryable)")
    void getAsyncApi_401_returnsRetryableException() {
        wireMock.stubFor(get(urlPathEqualTo("/api/test-api-123"))
                .willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> service.getAsyncApi())
                .isInstanceOf(RetryableException.class)
                .hasMessageContaining("Unauthorized");
    }

    @Test
    @DisplayName("500 с телом ошибки -> Wso2Exception")
    void getAsyncApi_500_withBody_returnsWso2Exception() {
        wireMock.stubFor(get(urlPathEqualTo("/api/test-api-123"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Internal error\",\"description\":\"DB timeout\"}")));

        assertThatThrownBy(() -> service.getAsyncApi())
                .isInstanceOf(Wso2Exception.class)
                .hasMessageContaining("DB timeout");
    }

    @Test
    @DisplayName("500 с пустым телом -> OBException 500")
    void getAsyncApi_500_emptyBody_returnsOBException() {
        wireMock.stubFor(get(urlPathEqualTo("/api/test-api-123"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> service.getAsyncApi())
                .isInstanceOf(OBException.class);
    }
}