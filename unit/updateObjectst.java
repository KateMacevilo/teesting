package com.example.consent.integration;

import com.example.consent.entity.*;
import com.example.consent.repository.*;
import com.example.consent.service.UpdateConsentStatusServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UpdateConsentStatusServiceImplIntegrationTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private UpdateConsentStatusServiceImpl updateConsentStatusService;

    @Autowired
    private ConsentRepository consentRepository;

    @Autowired
    private PartyUserRepository partyUserRepository;

    @Autowired
    private ConsentEventRepository consentEventRepository;

    private JdbcTemplate jdbcTemplate;

    @Autowired
    void setDataSource(DataSource ds) {
        this.jdbcTemplate = new JdbcTemplate(ds);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE TABLE transaction_webhook_consent_endpoint_rule, " +
                "transaction_webhook_consent_account, transaction_webhook_consent_event, " +
                "transaction_webhook_consent_endpoint, transaction_webhook_consent, " +
                "enduser, party_user RESTART IDENTITY CASCADE");
    }

    private PartyUserEntity createPartyUser() {
        PartyUserEntity pu = new PartyUserEntity();
        pu.setPartyUserId("pu-" + System.nanoTime());
        pu.setPartyUserName("Test Party");
        pu.setPartyUserCategory("INDIVIDUAL");
        pu.setTaxNumber("7700000000");
        pu.setFullLegalName("Full Legal Name");
        return partyUserRepository.save(pu);
    }

    private ConsentEntity createConsent(String status, LocalDate expiresOn, String topicName) {
        PartyUserEntity pu = createPartyUser();
        ConsentEntity c = new ConsentEntity();
        c.setConsentUuid(UUID.randomUUID());
        c.setClientId("client-001");
        c.setApiKeyId("apikey-001");
        c.setApplicationUuid(UUID.randomUUID());
        c.setClientName("TestApp");
        c.setSdboClientId(1);
        c.setEnvironment("API_KEY");
        c.setIdempotencyKey(UUID.randomUUID());
        c.setPartyUserEntity(pu);
        c.setCreatedOn(LocalDateTime.now());
        c.setUpdatedOn(LocalDateTime.now());
        c.setExpiresOn(expiresOn);
        c.setScopeCode("WEBHOOK_CONSENT");
        c.setStatusCode(status);
        c.setTopicName(topicName);
        return consentRepository.save(c);
    }

    @Test
    @DisplayName("200 OK — AUTHORISED -> ACTIVE")
    void updateStatusConsent_authorisedToActive_success() {
        ConsentEntity consent = createConsent("AUTHORISED", LocalDate.now().plusDays(1), "topic-1");
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("ACTIVE");

        ResponseEntity<?> response = updateConsentStatusService.updateStatusConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "UPDATE_STATUS"),
                consent.getConsentUuid().toString(),
                request);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        ConsentEntity updated = consentRepository.findById(consent.getConsentUuid()).orElseThrow();
        assertThat(updated.getStatusCode()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("200 OK — ACTIVE -> INVALIDATED, соседние consent с тем же topicName тоже инвалидируются")
    void updateStatusConsent_invalidatesOthersByTopicName() {
        ConsentEntity c1 = createConsent("ACTIVE", LocalDate.now().plusDays(1), "shared-topic");
        ConsentEntity c2 = createConsent("ACTIVE", LocalDate.now().plusDays(1), "shared-topic");

        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("INVALIDATED");

        ResponseEntity<?> response = updateConsentStatusService.updateStatusConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "UPDATE_STATUS"),
                c1.getConsentUuid().toString(),
                request);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        ConsentEntity updatedC2 = consentRepository.findById(c2.getConsentUuid()).orElseThrow();
        assertThat(updatedC2.getStatusCode()).isEqualTo("INVALIDATED");
    }

    @Test
    @DisplayName("400 — невалидный текущий статус consent (например AWAITING_AUTHORIZATION)")
    void updateStatusConsent_invalidCurrentStatus() {
        ConsentEntity consent = createConsent("AWAITING_AUTHORIZATION", LocalDate.now().plusDays(1), "topic-1");
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("ACTIVE");

        assertThatThrownBy(() -> updateConsentStatusService.updateStatusConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "UPDATE_STATUS"),
                consent.getConsentUuid().toString(),
                request))
                .isInstanceOf(SuiteException.class);
    }

    @Test
    @DisplayName("400 — просроченный consent")
    void updateStatusConsent_expired() {
        ConsentEntity consent = createConsent("AUTHORISED", LocalDate.now().minusDays(1), "topic-1");
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("ACTIVE");

        assertThatThrownBy(() -> updateConsentStatusService.updateStatusConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "UPDATE_STATUS"),
                consent.getConsentUuid().toString(),
                request))
                .isInstanceOf(SuiteException.class);
    }

    @Test
    @DisplayName("400 — невалидный переход статуса (ACTIVE -> AUTHORISED)")
    void updateStatusConsent_invalidTransition() {
        ConsentEntity consent = createConsent("ACTIVE", LocalDate.now().plusDays(1), "topic-1");
        UpdateStatusRequest request = new UpdateStatusRequest();
        request.setStatus("AUTHORISED");

        assertThatThrownBy(() -> updateConsentStatusService.updateStatusConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "UPDATE_STATUS"),
                consent.getConsentUuid().toString(),
                request))
                .isInstanceOf(SuiteException.class);
    }
}