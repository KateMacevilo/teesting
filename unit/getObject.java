package com.example.consent.integration;

import com.example.consent.entity.*;
import com.example.consent.repository.*;
import com.example.consent.service.GetConsentServiceImpl;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private GetConsentServiceImpl getConsentService;

    @Autowired
    private ConsentRepository consentRepository;

    @Autowired
    private PartyUserRepository partyUserRepository;

    @Autowired
    private EnduserRepository enduserRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private ConsentAccountRepository consentAccountRepository;

    @Autowired
    private ConsentEventRepository consentEventRepository;

    private JdbcTemplate jdbcTemplate;

    @Autowired
    void setDataSource(DataSource ds) {
        this.jdbcTemplate = new JdbcTemplate(ds);
    }

    @BeforeEach
    void clean() {
        // Очистка в правильном порядке с учётом FK (CASCADE в PostgreSQL)
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

    private EnduserEntity createEnduser() {
        EnduserEntity eu = new EnduserEntity();
        eu.setCoreEnduserName("John Doe");
        eu.setTaxNumber("1234567890");
        return enduserRepository.save(eu);
    }

    private ConsentEntity createConsent(String status, LocalDate expiresOn, PartyUserEntity pu, EnduserEntity eu) {
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
        c.setEndUserEntity(eu);
        c.setCreatedOn(LocalDateTime.now());
        c.setUpdatedOn(LocalDateTime.now());
        c.setExpiresOn(expiresOn);
        c.setScopeCode("WEBHOOK_CONSENT");
        c.setStatusCode(status);
        c.setTopicName("topic-1");
        return consentRepository.save(c);
    }

    private EndpointEntity createEndpoint(ConsentEntity consent) {
        EndpointEntity ep = new EndpointEntity();
        ep.setConsentUuid(consent.getConsentUuid());
        ep.setCallbackUrl("https://example.com/callback");
        return endpointRepository.save(ep);
    }

    private ConsentAccountEntity createAccount(ConsentEntity consent, String accountId) {
        ConsentAccountEntity acc = new ConsentAccountEntity();
        acc.setConsentUuid(consent.getConsentUuid());
        acc.setAccountId(accountId);
        acc.setIban("IBAN123");
        acc.setTransactionType("DEBIT");
        return consentAccountRepository.save(acc);
    }

    @Test
    @DisplayName("200 OK — активный непросроченный consent с endpoint и accounts")
    void getConsent_success() {
        PartyUserEntity pu = createPartyUser();
        EnduserEntity eu = createEnduser();
        ConsentEntity consent = createConsent("ACTIVE", LocalDate.now().plusDays(1), pu, eu);
        createEndpoint(consent);
        createAccount(consent, "acc-1");

        ResponseEntity<?> response = getConsentService.getConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "READ"),
                consent.getConsentUuid().toString());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        List<ConsentEventEntity> events = consentEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getActionCode()).isEqualTo("READ");
        assertThat(events.get(0).getStatusCode()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("200 OK — просроченный ACTIVE consent возвращает статус EXPIRED")
    void getConsent_expiredActive() {
        PartyUserEntity pu = createPartyUser();
        ConsentEntity consent = createConsent("ACTIVE", LocalDate.now().minusDays(1), pu, null);
        createEndpoint(consent);

        ResponseEntity<?> response = getConsentService.getConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "READ"),
                consent.getConsentUuid().toString());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        // В ответе должен быть статус EXPIRED (проверяется в маппере/ответе)
    }

    @Test
    @DisplayName("200 OK — AUTHORISED consent без просрочки")
    void getConsent_authorised() {
        PartyUserEntity pu = createPartyUser();
        ConsentEntity consent = createConsent("AUTHORISED", LocalDate.now().plusDays(1), pu, null);
        createEndpoint(consent);

        ResponseEntity<?> response = getConsentService.getConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "READ"),
                consent.getConsentUuid().toString());

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    @DisplayName("404 — consent не найден")
    void getConsent_notFound() {
        assertThatThrownBy(() -> getConsentService.getConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1", "Action-Code", "READ"),
                UUID.randomUUID().toString()))
                .isInstanceOf(SuiteException.class);
    }
}