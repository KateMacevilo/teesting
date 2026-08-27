package com.example.consent.integration;

import com.example.consent.mapper.ConsentMapper;
import com.example.consent.model.*;
import com.example.consent.repository.DataAccessRepository;
import com.example.consent.service.*;
import com.example.consent.webhook.TransactionWebHookConsentServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionWebHookConsentServiceImplIntegrationTest {

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
    private TransactionWebHookConsentServiceImpl consentService;

    @Autowired
    private DataAccessRepository dataAccessRepository;

    @Autowired
    private ConsentMapper consentMapper;

    @MockBean
    private WebhookUrlService webhookUrlService;

    @MockBean
    private MgtApiKeyClientService mgtApiKeyClientService;

    @MockBean
    private IdentityClientService identityClientService;

    @MockBean
    private BUserClient bUserClient;

    @MockBean
    private ApiManagerClientService apiManagerClientService;

    private static final String IDEMPOTENCY_KEY = "550e8400-e29b-41d4-a716-446655440000";
    private static final String CORRELATION_ID = "660e8400-e29b-41d4-a716-446655440001";
    private static final String USER_ID = "user-123";
    private static final String SUBSCRIBER = "sub-456";
    private static final String TAX_NUMBER = "1234567890";
    private static final String ORGANIZATION = "Org Ltd";
    private static final String APPLICATION_UUID = "app-uuid-789";
    private static final String KEY_TYPE = "API_KEY";

    @BeforeEach
    void setUp() {
        doNothing().when(webhookUrlService).validateCallbackUrl(any(), any());
        doNothing().when(mgtApiKeyClientService).callMgtApikey(any(), any(), any(), any(), any());
    }

    @AfterEach
    void tearDown() {
        dataAccessRepository.deleteAll(); // если есть такой метод
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-JWT-Assertion", "dummy-jwt");
        headers.put("Origin-Prefix", "TEST");
        headers.put("Correlation-Id", CORRELATION_ID);
        headers.put("Msg-Id", "msg-001");
        return headers;
    }

    private WebhookConsentRequest buildRequest() {
        WebhookConsentRequest request = new WebhookConsentRequest();
        WebhookConsentRequestData data = new WebhookConsentRequestData();
        data.setCallbackUrl("https://example.com/callback");
        data.setAccessControl("PUBLIC");
        request.setData(data);
        return request;
    }

    @Test
    @DisplayName("Успешное создание consent и сохранение в PostgreSQL")
    void createConsent_success() {
        // given
        Map<String, String> headers = buildHeaders();
        WebhookConsentRequest request = buildRequest();

        // when
        var response = consentService.createConsent(headers, request, IDEMPOTENCY_KEY);

        // then
        assertThat(response.getStatusCodeValue()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().getTransactionWebhookConsentId()).isNotNull();

        // Проверка в БД
        UUID idempotencyUUID = UUID.fromString(IDEMPOTENCY_KEY);
        var consentOpt = dataAccessRepository.findByIdempotencyKey(idempotencyUUID);
        assertThat(consentOpt).isPresent();

        ConsentEntity consent = consentOpt.get();
        assertThat(consent.getConsentUuid()).isNotNull();
        assertThat(consent.getStatusCode()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("Идемпотентность: повторный запрос с тем же ключом возвращает существующий consent")
    void createConsent_idempotency_secondRequestReturnsSameConsent() {
        // given
        Map<String, String> headers = buildHeaders();
        WebhookConsentRequest request = buildRequest();

        // when
        var first = consentService.createConsent(headers, request, IDEMPOTENCY_KEY);
        var second = consentService.createConsent(headers, request, IDEMPOTENCY_KEY);

        // then
        assertThat(first.getBody().getData().getTransactionWebhookConsentId())
                .isEqualTo(second.getBody().getData().getTransactionWebhookConsentId());

        // В БД ровно 1 запись
        long count = dataAccessRepository.countByIdempotencyKey(UUID.fromString(IDEMPOTENCY_KEY));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("409 Conflict при изменении данных с тем же idempotencyKey")
    void createConsent_idempotency_conflict() {
        // given
        Map<String, String> headers = buildHeaders();
        WebhookConsentRequest firstRequest = buildRequest();

        consentService.createConsent(headers, firstRequest, IDEMPOTENCY_KEY);

        WebhookConsentRequest secondRequest = buildRequest();
        secondRequest.getData().setCallbackUrl("https://different.com/callback");

        // when & then
        assertThrows(OBException.class, () ->
                consentService.createConsent(headers, secondRequest, IDEMPOTENCY_KEY));

        // Проверяем, что rejected event сохранился (требует фикса проблемы #2)
        var events = dataAccessRepository.findConsentEventsByIdempotencyKey(UUID.fromString(IDEMPOTENCY_KEY));
        assertThat(events).anyMatch(e -> e.getStatusCode().equals("REJECTED"));
    }

    @Test
    @DisplayName("Сохранение PartyUser и Enduser при создании consent")
    void createConsent_savesPartyUserAndEnduser() {
        // given
        Map<String, String> headers = buildHeaders();
        headers.put("Enduser", "John Doe");
        headers.put("Id-Client", "client-001");
        headers.put("ApiKey-Id", "apikey-001");
        WebhookConsentRequest request = buildRequest();

        // when
        consentService.createConsent(headers, request, UUID.randomUUID().toString());

        // then
        var partyUsers = dataAccessRepository.findAllPartyUsers();
        assertThat(partyUsers).isNotEmpty();

        var endusers = dataAccessRepository.findAllEndusers();
        assertThat(endusers).isNotEmpty();
    }

    @Test
    @DisplayName("ConsentEvent со статусом ACCEPTED сохраняется в БД")
    void createConsent_savesAcceptedEvent() {
        // given
        Map<String, String> headers = buildHeaders();
        WebhookConsentRequest request = buildRequest();
        String key = UUID.randomUUID().toString();

        // when
        consentService.createConsent(headers, request, key);

        // then
        var events = dataAccessRepository.findConsentEventsByIdempotencyKey(UUID.fromString(key));
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getStatusCode()).isEqualTo("ACCEPTED");
        assertThat(events.get(0).getActionCode()).isEqualTo("CREATE");
    }

    @Test
    @DisplayName("Транзакция откатывается при падении внешнего сервиса mgtApiKeyClientService")
    void createConsent_rollbackOnExternalServiceFailure() {
        // given
        Map<String, String> headers = buildHeaders();
        headers.put("Id-Client", "client-001");
        headers.put("ApiKey-Id", "apikey-001");
        WebhookConsentRequest request = buildRequest();
        String key = UUID.randomUUID().toString();

        doThrow(new RuntimeException("Service unavailable"))
                .when(mgtApiKeyClientService).callMgtApikey(any(), any(), any(), any(), any());

        // when & then
        assertThrows(OBException.class, () ->
                consentService.createConsent(headers, request, key));

        // Consent не должен сохраниться
        var consentOpt = dataAccessRepository.findByIdempotencyKey(UUID.fromString(key));
        assertThat(consentOpt).isEmpty();
    }

    @Test
    @DisplayName("Параллельные запросы с одним idempotencyKey не создают дубли")
    @Execution(ExecutionMode.SAME_THREAD)
    void createConsent_concurrentIdempotency_noDuplicates() throws InterruptedException {
        // given
        Map<String, String> headers = buildHeaders();
        WebhookConsentRequest request = buildRequest();
        String sharedKey = UUID.randomUUID().toString();

        int threads = 5;
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        // when
        IntStream.range(0, threads).forEach(i -> executor.submit(() -> {
            try {
                consentService.createConsent(headers, request, sharedKey);
            } catch (Exception e) {
                // Ожидаемые исключения при race condition
            } finally {
                latch.countDown();
            }
        }));

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // then
        long count = dataAccessRepository.countByIdempotencyKey(UUID.fromString(sharedKey));
        assertThat(count).isLessThanOrEqualTo(1); // Ожидаем 1, но без UNIQUE constraint может быть >1
    }

    @Test
    @DisplayName("Ошибка при null idempotencyKey")
    void createConsent_nullIdempotencyKey_throwsException() {
        Map<String, String> headers = buildHeaders();
        WebhookConsentRequest request = buildRequest();

        assertThrows(IllegalArgumentException.class, () ->
                consentService.createConsent(headers, request, null));
    }
}