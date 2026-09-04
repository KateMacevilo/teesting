package com.example.consent.integration;

import com.example.consent.entity.*;
import com.example.consent.repository.*;
import com.example.consent.service.ApiManagerClientService;
import com.example.consent.service.UpdateConsentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
@Sql(scripts = "/schema.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UpdateConsentServiceImplIntegrationTest {

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
    private UpdateConsentServiceImpl updateConsentService;

    @Autowired
    private ConsentRepository consentRepository;

    @Autowired
    private PartyUserRepository partyUserRepository;

    @Autowired
    private EndpointRepository endpointRepository;

    @Autowired
    private ConsentEventRepository consentEventRepository;

    @MockBean
    private ApiManagerClientService apiManagerClientService;

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

    private ConsentEntity createAwaitingAuthConsent(PartyUserEntity pu) {
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
        c.setExpiresOn(LocalDate.now().plusDays(1));
        c.setScopeCode("WEBHOOK_CONSENT");
        c.setStatusCode("AWAITING_AUTHORIZATION");
        c.setTopicName(null);
        return consentRepository.save(c);
    }

    private EndpointEntity createEndpoint(ConsentEntity consent) {
        EndpointEntity ep = new EndpointEntity();
        ep.setConsentUuid(consent.getConsentUuid());
        ep.setCallbackUrl("https://example.com/callback");
        return endpointRepository.save(ep);
    }

    @Test
    @DisplayName("204 — обновление до AUTHORISED (создание топика, callback в whitelist)")
    void updateConsent_authorised_success() {
        PartyUserEntity pu = createPartyUser();
        ConsentEntity consent = createAwaitingAuthConsent(pu);
        createEndpoint(consent);

        when(apiManagerClientService.getAsyncApi()).thenReturn(new AsyncApiDto());
        when(apiManagerClientService.updateTopics(any())).thenReturn(new AsyncApiDto());
        when(apiManagerClientService.getRevisions()).thenReturn(List.of());
        when(apiManagerClientService.postRevision(any())).thenReturn(new AmRevision());
        when(apiManagerClientService.getAsyncApi()).thenReturn(new AsyncApiDto());

        ResponseEntity<?> response = updateConsentService.updateConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1"),
                consent.getConsentUuid().toString(),
                List.of()); // пустой jsonPatch — мок consentMapper.getRequestParams в unit, тут через @MockBean если нужно

        assertThat(response.getStatusCodeValue()).isEqualTo(204);
    }

    @Test
    @DisplayName("400 — consent не в статусе AWAITING_AUTHORIZATION")
    void updateConsent_invalidStatus() {
        PartyUserEntity pu = createPartyUser();
        ConsentEntity consent = createAwaitingAuthConsent(pu);
        consent.setStatusCode("ACTIVE");
        consentRepository.save(consent);

        assertThatThrownBy(() -> updateConsentService.updateConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1"),
                consent.getConsentUuid().toString(),
                List.of()))
                .isInstanceOf(SuiteException.class);
    }

    @Test
    @DisplayName("400 — просроченный consent")
    void updateConsent_expired() {
        PartyUserEntity pu = createPartyUser();
        ConsentEntity consent = createAwaitingAuthConsent(pu);
        consent.setExpiresOn(LocalDate.now().minusDays(1));
        consentRepository.save(consent);

        assertThatThrownBy(() -> updateConsentService.updateConsent(
                Map.of("Origin-Prefix", "TEST", "Correlation-Id", "c1"),
                consent.getConsentUuid().toString(),
                List.of()))
                .isInstanceOf(SuiteException.class);
    }
}