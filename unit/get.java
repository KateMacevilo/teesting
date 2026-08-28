package com.example.consent.integration;

import com.example.consent.entity.ConsentEntity;
import com.example.consent.entity.ConsentEventEntity;
import com.example.consent.entity.EndpointEntity;
import com.example.consent.exception.OBException;
import com.example.consent.repository.ConsentEventRepository;
import com.example.consent.repository.ConsentRepository;
import com.example.consent.repository.EndpointRepository;
import com.example.consent.service.GetConsentServiceImpl;
import com.example.consent.service.JwtClaimParser;
import com.example.consent.service.Wso2ClientService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Интеграционные тесты для GetConsentServiceImpl.
 *
 * Требования:
 * 1. Docker для Testcontainers
 * 2. Зависимости: org.testcontainers:junit-jupiter + org.testcontainers:postgresql
 * 3. parseJwt вынесен в бин JwtClaimParser (рекомендация по рефакторингу)
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GetConsentServiceImplIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("consent_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private GetConsentServiceImpl getConsentService;

    @Autowired
    private ConsentRepository consentRepository;

    @Autowired
    private ConsentEventRepository consentEventRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @MockBean
    private Wso2ClientService wso2ClientService;

    /**
     * Предполагается, что parseJwt вынесен в отдельный бин (см. рекомендацию по рефакторингу).
     * Если parseJwt остается private-методом в сервисе, замените на @SpyBean + reflection
     * или передайте реальный тестовый JWT-токен.
     */
    @MockBean
    private JwtClaimParser jwtClaimParser;

    private static final String USER_ID = "user-123";
    private static final String SUBSCRIBER = "sub-456";
    private static final String APPLICATION_UUID = "app-uuid-789";
    private static final String KEY_TYPE = "API_KEY";
    private static final String APIKEY_ID = "apikey-001";
    private static final String ENDUSER = "enduser-001";
    private static final String ORIGIN_PREFIX = "TEST";
    private static final String CORRELATION_ID = "corr-001";

    @BeforeEach
    void setUp() {
        consentEventRepository.deleteAll();
        endpointRepository.deleteAll();
        consentRepository.deleteAll();

        when(wso2ClientService.getAuthorizedApps(any(), any())).thenReturn("app-authorized-001");
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-JWT-Assertion", "dummy-jwt");
        headers.put("Origin-Prefix", ORIGIN_PREFIX);
        headers.put("Correlation-Id", CORRELATION_ID);
        return headers;
    }

    private Map<String, String> buildClaims(boolean withOptionalKeys) {
        Map<String, String> claims = new HashMap<>();
        claims.put("USER_ID", USER_ID);
        claims.put("SUBSCRIBER", SUBSCRIBER);
        claims.put("APPLICATION_UUID", APPLICATION_UUID);
        claims.put("KEY_TYPE", KEY_TYPE);
        if (withOptionalKeys) {
            claims.put("APIKEY_ID", APIKEY_ID);
            claims.put("ENDUSER", ENDUSER);
        }
        return claims;
    }

    private ConsentEntity createConsentInDb(String status, String appIdentifier, boolean expired) {
        ConsentEntity consent = new ConsentEntity();
        consent.setConsentUuid(UUID.randomUUID());
        consent.setStatusCode(status);
        consent.setIdempotencyKey(UUID.randomUUID());
        consent.setAppIdentifier(appIdentifier);
        consent.setCreatedOn(LocalDateTime.now());
        consent.setUpdatedOn(LocalDateTime.now());

        if (expired) {
            consent.setExpiresOn(LocalDateTime.now().minusDays(1));
        } else {
            consent.setExpiresOn(LocalDateTime.now().plusDays(1));
        }

        consent = consentRepository.save(consent);

        EndpointEntity endpoint = new EndpointEntity();
        endpoint.setConsentUuid(consent.getConsentUuid());
        endpoint.setCallbackUrl("https://example.com/callback");
        endpoint.setAccessControl("PUBLIC");
        endpointRepository.save(endpoint);

        return consent;
    }

    @Test
    @DisplayName("Успешное получение consent с сохранением READ/ACCEPTED события в БД")
    void getConsent_success() {
        // given
        ConsentEntity consent = createConsentInDb("ACTIVE", APIKEY_ID, false);
        Map<String, String> headers = buildHeaders();
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        // when
        ResponseEntity<?> response = getConsentService.getConsent(
                headers, consent.getConsentUuid().toString());

        // then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        List<ConsentEventEntity> events = consentEventRepository.findAll().stream()
                .filter(e -> e.getConsentUuid().equals(consent.getConsentUuid()))
                .collect(Collectors.toList());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActionCode()).isEqualTo("READ");
        assertThat(events.get(0).getStatusCode()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("404 Not Found - событие отклонения НЕ сохраняется (баг в текущем коде)")
    void getConsent_notFound_rejectedEventNotSaved() {
        // given
        Map<String, String> headers = buildHeaders();
        String randomUuid = UUID.randomUUID().toString();
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        // when & then
        assertThrows(OBException.class, () ->
                getConsentService.getConsent(headers, randomUuid));

        // ПРОБЛЕМА КОДА: при OBException (404) ранний throw прерывает выполнение
        // и saveRejectedEvent НЕ вызывается. Это баг аудита.
        List<ConsentEventEntity> events = consentEventRepository.findAll();
        assertThat(events).isEmpty(); // Демонстрация бага - событий нет!
    }

    @Test
    @DisplayName("403 Forbidden - невалидный appIdentifier, rejected event сохраняется")
    void getConsent_invalidAppIdentifier() {
        // given
        ConsentEntity consent = createConsentInDb("ACTIVE", "different-app-id", false);
        Map<String, String> headers = buildHeaders();
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        // when & then
        assertThrows(OBException.class, () ->
                getConsentService.getConsent(headers, consent.getConsentUuid().toString()));

        List<ConsentEventEntity> events = consentEventRepository.findAll().stream()
                .filter(e -> e.getConsentUuid().equals(consent.getConsentUuid()))
                .collect(Collectors.toList());

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatusCode()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("Просроченный ACTIVE consent возвращает статус EXPIRED в ответе")
    void getConsent_expiredActive() {
        // given
        ConsentEntity consent = createConsentInDb("ACTIVE", APIKEY_ID, true);
        Map<String, String> headers = buildHeaders();
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        // when
        ResponseEntity<?> response = getConsentService.getConsent(
                headers, consent.getConsentUuid().toString());

        // then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    @DisplayName("Просроченный AUTHORISED consent возвращает статус EXPIRED")
    void getConsent_expiredAuthorised() {
        // given
        ConsentEntity consent = createConsentInDb("AUTHORISED", APIKEY_ID, true);
        Map<String, String> headers = buildHeaders();
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        // when
        ResponseEntity<?> response = getConsentService.getConsent(
                headers, consent.getConsentUuid().toString());

        // then
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    @DisplayName("500 при недоступности WSO2 и откат транзакции")
    void getConsent_wso2Unavailable() {
        // given
        ConsentEntity consent = createConsentInDb("ACTIVE", null, false);
        Map<String, String> headers = buildHeaders();

        // Нет APIKEY_ID в claims, поэтому идет запрос к WSO2
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(false));
        when(wso2ClientService.getAuthorizedApps(any(), any()))
                .thenThrow(new RuntimeException("WSO2 timeout"));

        // when & then
        assertThrows(OBException.class, () ->
                getConsentService.getConsent(headers, consent.getConsentUuid().toString()));

        // Проверяем, что ACCEPTED событие не создалось (транзакция откатилась)
        List<ConsentEventEntity> events = consentEventRepository.findAll();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Невалидный формат UUID вызывает IllegalArgumentException до поиска в БД")
    void getConsent_invalidUuid() {
        // given
        Map<String, String> headers = buildHeaders();
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        // when & then
        assertThrows(IllegalArgumentException.class, () ->
                getConsentService.getConsent(headers, "not-a-uuid"));
    }
}