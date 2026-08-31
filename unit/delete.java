package com.example.consent.integration;

import com.example.consent.entity.*;
import com.example.consent.exception.OBException;
import com.example.consent.service.*;
import jakarta.persistence.EntityManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Интеграционные тесты для DeleteConsentServiceImpl.
 * Требует Docker. Использует реальную PostgreSQL через Testcontainers.
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeleteConsentServiceImplIntegrationTest {

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
    private DeleteConsentServiceImpl deleteConsentService;

    @Autowired
    private EntityManager em;

    @MockBean
    private Wso2ClientService wso2ClientService;

    @MockBean
    private MgtSubscriptionClientService mgtSubscriptionClient;

    @MockBean
    private JwtClaimParser jwtClaimParser;

    private static final String USER_ID = "user-123";
    private static final String APIKEY_ID = "apikey-001";
    private static final String ORIGIN_PREFIX = "TEST";
    private static final String CORRELATION_ID = "550e8400-e29b-41d4-a716-446655440001";

    @BeforeEach
    void setUp() {
        // Очистка БД
        em.createQuery("delete from ConsentEventEntity").executeUpdate();
        em.createQuery("delete from EndpointEntity").executeUpdate();
        em.createQuery("delete from ConsentEntity").executeUpdate();
        em.createQuery("delete from PartyUserEntity").executeUpdate();
        em.createQuery("delete from EnduserEntity").executeUpdate();
        em.flush();

        // Дефолтные моки для happy path
        when(wso2ClientService.getAuthorizedApps(any(), any())).thenReturn(APIKEY_ID);
        when(wso2ClientService.getAsyncApi()).thenReturn(new AsyncApiDto());
        doNothing().when(wso2ClientService).updateTopics(any());
        when(wso2ClientService.getRevisions()).thenReturn(Collections.emptyList());
        when(wso2ClientService.postRevision(any())).thenReturn(new AmRevision());
        doNothing().when(wso2ClientService).deployRevision(any());

        SuiteResponse<MetaDefault, SubscriptionData> mockResponse = new SuiteResponse<>();
        mockResponse.setData(new SubscriptionData());
        mockResponse.getData().setStatus("INACTIVE"); // чтобы не вызывался deactivate
        when(mgtSubscriptionClient.getSubscription(any(), any(), any(), any(), any())).thenReturn(mockResponse);
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("X-JWT-Assertion", "dummy");
        h.put("Origin-Prefix", ORIGIN_PREFIX);
        h.put("Correlation-Id", CORRELATION_ID);
        h.put("Msg-Id", "msg-001");
        return h;
    }

    private Map<String, String> buildClaims(boolean withOptionalKeys) {
        Map<String, String> c = new HashMap<>();
        c.put("USER_ID", USER_ID);
        c.put("SUBSCRIBER", "sub-001");
        c.put("APPLICATION_UUID", UUID.randomUUID().toString());
        c.put("KEY_TYPE", "API_KEY");
        if (withOptionalKeys) {
            c.put("APIKEY_ID", APIKEY_ID);
            c.put("ENDUSER", "enduser-001");
        }
        return c;
    }

    private ConsentEntity createConsent(String status, boolean expired, String apiKeyId) {
        ConsentEntity consent = new ConsentEntity();
        consent.setConsentUuid(UUID.randomUUID());
        consent.setIdempotencyKey(UUID.randomUUID());
        consent.setStatusCode(status);
        consent.setApiKeyId(apiKeyId);
        consent.setApplicationUuid(UUID.randomUUID());
        consent.setClientName("TestApp");
        consent.setEnvironment("API_KEY");
        consent.setScopeCode("WEBHOOK_CONSENT");
        consent.setCreatedOn(LocalDateTime.now());
        consent.setUpdatedOn(LocalDateTime.now());
        consent.setExpiresOn(expired ? LocalDateTime.now().minusDays(1) : LocalDateTime.now().plusDays(1));
        em.persist(consent);
        em.flush();
        return consent;
    }

    @SuppressWarnings("unchecked")
    private List<ConsentEventEntity> findEventsByConsentUuid(UUID consentUuid) {
        return em.createQuery(
                        "select e from ConsentEventEntity e where e.consentUuid = :uuid order by e.createdOn desc")
                .setParameter("uuid", consentUuid)
                .getResultList();
    }

    // ============ ТЕСТЫ ============

    @Test
    @DisplayName("Успешное удаление: 204, статус REVOKED, событие REVOKE/ACCEPTED")
    void deleteConsent_success() {
        ConsentEntity consent = createConsent("ACTIVE", false, APIKEY_ID);
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        ResponseEntity<?> response = deleteConsentService.deleteConsent(
                buildHeaders(), consent.getConsentUuid().toString());

        assertThat(response.getStatusCodeValue()).isEqualTo(204);

        ConsentEntity updated = em.find(ConsentEntity.class, consent.getConsentUuid());
        assertThat(updated.getStatusCode()).isEqualTo("REVOKED");

        List<ConsentEventEntity> events = findEventsByConsentUuid(consent.getConsentUuid());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActionCode()).isEqualTo("REVOKE");
        assertThat(events.get(0).getStatusCode()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("404 Not Found — rejected event НЕ сохраняется (баг аудита)")
    void deleteConsent_notFound_noAuditEvent() {
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));
        String randomUuid = UUID.randomUUID().toString();

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), randomUuid));

        // Баг: при OBException ранний throw прерывает saveRejectedEvent
        List<ConsentEventEntity> events = em.createQuery(
                        "select e from ConsentEventEntity e", ConsentEventEntity.class)
                .getResultList();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("403 Forbidden — невалидный appIdentifier, rejected event сохраняется")
    void deleteConsent_invalidAppIdentifier_403() {
        ConsentEntity consent = createConsent("ACTIVE", false, "different-api-key");
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), consent.getConsentUuid().toString()));

        List<ConsentEventEntity> events = findEventsByConsentUuid(consent.getConsentUuid());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatusCode()).isEqualTo("REJECTED");
        assertThat(events.get(0).getActionCode()).isEqualTo("REVOKE");
    }

    @Test
    @DisplayName("400 Bad Request — статус AWAITING_AUTHORIZATION, не просрочен")
    void deleteConsent_incorrectStatus_400() {
        ConsentEntity consent = createConsent("AWAITING_AUTHORIZATION", false, APIKEY_ID);
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), consent.getConsentUuid().toString()));

        List<ConsentEventEntity> events = findEventsByConsentUuid(consent.getConsentUuid());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatusCode()).isEqualTo("REJECTED");
        assertThat(events.get(0).getActionCode()).isEqualTo("REVOKE");

        // Статус consent не должен измениться
        ConsentEntity unchanged = em.find(ConsentEntity.class, consent.getConsentUuid());
        assertThat(unchanged.getStatusCode()).isEqualTo("AWAITING_AUTHORIZATION");
    }

    @Test
    @DisplayName("400 Bad Request — просроченный AWAITING_AUTHORIZATION")
    void deleteConsent_expiredIncorrectStatus_400() {
        ConsentEntity consent = createConsent("AWAITING_AUTHORIZATION", true, APIKEY_ID);
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), consent.getConsentUuid().toString()));

        List<ConsentEventEntity> events = findEventsByConsentUuid(consent.getConsentUuid());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatusCode()).isEqualTo("REJECTED");
        assertThat(events.get(0).getActionCode()).isEqualTo("REVOKE");

        ConsentEntity unchanged = em.find(ConsentEntity.class, consent.getConsentUuid());
        assertThat(unchanged.getStatusCode()).isEqualTo("AWAITING_AUTHORIZATION");
    }

    @Test
    @DisplayName("500 при ошибке WSO2 в deleteWebhookTopic, транзакция откатывается")
    void deleteConsent_wso2WebhookTopicError_500() {
        ConsentEntity consent = createConsent("ACTIVE", false, APIKEY_ID);
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));
        when(wso2ClientService.getAsyncApi()).thenThrow(new RuntimeException("WSO2 timeout"));

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), consent.getConsentUuid().toString()));

        // Транзакция откатилась — статус не REVOKED
        ConsentEntity unchanged = em.find(ConsentEntity.class, consent.getConsentUuid());
        assertThat(unchanged.getStatusCode()).isEqualTo("ACTIVE");

        // ACCEPTED событие не создалось
        List<ConsentEventEntity> events = findEventsByConsentUuid(consent.getConsentUuid());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("500 при ошибке деактивации подписки, транзакция откатывается")
    void deleteConsent_subscriptionError_500() {
        ConsentEntity consent = createConsent("ACTIVE", false, APIKEY_ID);
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(true));
        when(mgtSubscriptionClient.getSubscription(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Subscription service down"));

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), consent.getConsentUuid().toString()));

        ConsentEntity unchanged = em.find(ConsentEntity.class, consent.getConsentUuid());
        assertThat(unchanged.getStatusCode()).isEqualTo("ACTIVE");

        List<ConsentEventEntity> events = findEventsByConsentUuid(consent.getConsentUuid());
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("500 при недоступности WSO2 для получения authorized apps")
    void deleteConsent_wso2AuthorizedAppsError_500() {
        ConsentEntity consent = createConsent("ACTIVE", false, null);
        when(jwtClaimParser.extractClaims(any(), any())).thenReturn(buildClaims(false));
        when(wso2ClientService.getAuthorizedApps(any(), any()))
                .thenThrow(new RuntimeException("WSO2 unavailable"));

        assertThrows(OBException.class, () ->
                deleteConsentService.deleteConsent(buildHeaders(), consent.getConsentUuid().toString()));

        ConsentEntity unchanged = em.find(ConsentEntity.class, consent.getConsentUuid());
        assertThat(unchanged.getStatusCode()).isEqualTo("ACTIVE");
    }
}