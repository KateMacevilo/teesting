package com.example.consent.testdata;

import com.example.consent.entity.*;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Фабрика тестовых данных для интеграционных тестов.
 * Создаёт и сохраняет в БД (через EntityManager) связный граф сущностей Consent.
 *
 * <p>Пример использования:
 * <pre>
 * ConsentTestDataFactory factory = new ConsentTestDataFactory(entityManager);
 *
 * // Минимальный вариант — consent со всеми дефолтами
 * ConsentEntity consent = factory.aConsent().buildAndSave();
 *
 * // Расширенный — с кастомизацией полей и связей
 * ConsentEntity consent = factory.aConsent()
 *     .withStatus("ACTIVE")
 *     .withPartyUser(factory.aPartyUser().withUserId("user-123").buildAndSave())
 *     .withEnduser(factory.anEnduser().withEnduserName("John Doe").withTaxNumber("1234567890").buildAndSave())
 *     .withEndpoint(factory.anEndpoint()
 *         .withCallbackUrl("https://example.com/callback")
 *         .withIpRule("192.168.1.1")
 *         .withCidrRule("10.0.0.0/8")
 *         .buildAndSave())
 *     .withEvent(factory.anEvent()
 *         .withActionCode("READ")
 *         .withStatusCode("ACCEPTED")
 *         .buildAndSave())
 *     .buildAndSave();
 * </pre>
 */
public class ConsentTestDataFactory {

    private final EntityManager em;

    public ConsentTestDataFactory(EntityManager em) {
        this.em = em;
    }

    /* =========================== BUILDERS =========================== */

    public ConsentBuilder aConsent() {
        return new ConsentBuilder(this);
    }

    public PartyUserBuilder aPartyUser() {
        return new PartyUserBuilder(this);
    }

    public EnduserBuilder anEnduser() {
        return new EnduserBuilder(this);
    }

    public EndpointBuilder anEndpoint() {
        return new EndpointBuilder(this);
    }

    public ConsentEventBuilder anEvent() {
        return new ConsentEventBuilder(this);
    }

    /* =========================== CONSENT =========================== */

    public class ConsentBuilder {
        private final ConsentTestDataFactory factory;
        private UUID consentUuid = UUID.randomUUID();
        private UUID idempotencyKey = UUID.randomUUID();
        private String statusCode = "AWAITING_AUTHORIZATION";
        private String scopeCode = "WEBHOOK_CONSENT";
        private String apiKeyId;
        private String clientId;
        private UUID applicationUuid = UUID.randomUUID();
        private String clientName = "TestApp";
        private String environment = "API_KEY";
        private LocalDateTime createdOn = LocalDateTime.now();
        private LocalDateTime updatedOn = LocalDateTime.now();
        private LocalDateTime expiresOn = LocalDateTime.now().plusDays(30);
        private String topicName;
        private Integer sdboClientId;
        private PartyUserEntity partyUser;
        private EnduserEntity enduser;

        private ConsentBuilder(ConsentTestDataFactory factory) {
            this.factory = factory;
        }

        public ConsentBuilder withConsentUuid(UUID uuid) {
            this.consentUuid = uuid;
            return this;
        }

        public ConsentBuilder withIdempotencyKey(UUID key) {
            this.idempotencyKey = key;
            return this;
        }

        public ConsentBuilder withStatus(String status) {
            this.statusCode = status;
            return this;
        }

        public ConsentBuilder withScopeCode(String scope) {
            this.scopeCode = scope;
            return this;
        }

        public ConsentBuilder withApiKeyId(String apiKeyId) {
            this.apiKeyId = apiKeyId;
            return this;
        }

        public ConsentBuilder withClientId(String clientId) {
            this.clientId = clientId;
            return this;
        }

        public ConsentBuilder withApplicationUuid(UUID uuid) {
            this.applicationUuid = uuid;
            return this;
        }

        public ConsentBuilder withClientName(String name) {
            this.clientName = name;
            return this;
        }

        public ConsentBuilder withEnvironment(String env) {
            this.environment = env;
            return this;
        }

        public ConsentBuilder withExpiresOn(LocalDateTime expires) {
            this.expiresOn = expires;
            return this;
        }

        public ConsentBuilder withTopicName(String topic) {
            this.topicName = topic;
            return this;
        }

        public ConsentBuilder withSdboClientId(Integer id) {
            this.sdboClientId = id;
            return this;
        }

        public ConsentBuilder withPartyUser(PartyUserEntity partyUser) {
            this.partyUser = partyUser;
            return this;
        }

        public ConsentBuilder withEnduser(EnduserEntity enduser) {
            this.enduser = enduser;
            return this;
        }

        public ConsentEntity build() {
            ConsentEntity e = new ConsentEntity();
            e.setConsentUuid(consentUuid);
            e.setIdempotencyKey(idempotencyKey);
            e.setStatusCode(statusCode);
            e.setScopeCode(scopeCode);
            e.setApiKeyId(apiKeyId);
            e.setClientId(clientId);
            e.setApplicationUuid(applicationUuid);
            e.setClientName(clientName);
            e.setEnvironment(environment);
            e.setCreatedOn(createdOn);
            e.setUpdatedOn(updatedOn);
            e.setExpiresOn(expiresOn);
            e.setTopicName(topicName);
            e.setSdboClientId(sdboClientId);
            e.setPartyUserEntity(partyUser);
            e.setEndUserEntity(enduser);
            return e;
        }

        public ConsentEntity buildAndSave() {
            ConsentEntity e = build();
            em.persist(e);
            em.flush();
            return e;
        }
    }

    /* =========================== PARTY USER =========================== */

    public class PartyUserBuilder {
        private final ConsentTestDataFactory factory;
        private String userId = "default-user-id";

        private PartyUserBuilder(ConsentTestDataFactory factory) {
            this.factory = factory;
        }

        public PartyUserBuilder withUserId(String userId) {
            this.userId = userId;
            return this;
        }

        public PartyUserEntity build() {
            PartyUserEntity e = new PartyUserEntity();
            e.setUserId(userId);
            return e;
        }

        public PartyUserEntity buildAndSave() {
            PartyUserEntity e = build();
            em.persist(e);
            em.flush();
            return e;
        }
    }

    /* =========================== ENDUSER =========================== */

    public class EnduserBuilder {
        private final ConsentTestDataFactory factory;
        private String enduserName;
        private String taxNumber;

        private EnduserBuilder(ConsentTestDataFactory factory) {
            this.factory = factory;
        }

        public EnduserBuilder withEnduserName(String name) {
            this.enduserName = name;
            return this;
        }

        public EnduserBuilder withTaxNumber(String tax) {
            this.taxNumber = tax;
            return this;
        }

        public EnduserEntity build() {
            EnduserEntity e = new EnduserEntity();
            e.setEnduserName(enduserName);
            e.setTaxNumber(taxNumber);
            return e;
        }

        public EnduserEntity buildAndSave() {
            EnduserEntity e = build();
            em.persist(e);
            em.flush();
            return e;
        }
    }

    /* =========================== ENDPOINT =========================== */

    public class EndpointBuilder {
        private final ConsentTestDataFactory factory;
        private UUID consentUuid = UUID.randomUUID();
        private String callbackUrl = "https://example.com/callback";
        private List<EndpointRuleEntity> rules = new ArrayList<>();

        private EndpointBuilder(ConsentTestDataFactory factory) {
            this.factory = factory;
        }

        public EndpointBuilder withConsentUuid(UUID uuid) {
            this.consentUuid = uuid;
            return this;
        }

        public EndpointBuilder withCallbackUrl(String url) {
            this.callbackUrl = url;
            return this;
        }

        public EndpointBuilder withIpRule(String ip) {
            EndpointRuleEntity rule = new EndpointRuleEntity();
            rule.setRuleType("IP");
            rule.setValue(ip);
            rules.add(rule);
            return this;
        }

        public EndpointBuilder withCidrRule(String cidr) {
            EndpointRuleEntity rule = new EndpointRuleEntity();
            rule.setRuleType("CIDR");
            rule.setValue(cidr);
            rules.add(rule);
            return this;
        }

        public EndpointBuilder withRule(String type, String value) {
            EndpointRuleEntity rule = new EndpointRuleEntity();
            rule.setRuleType(type);
            rule.setValue(value);
            rules.add(rule);
            return this;
        }

        public EndpointEntity build() {
            EndpointEntity e = new EndpointEntity();
            e.setConsentUuid(consentUuid);
            e.setCallbackUrl(callbackUrl);
            e.setRules(rules);
            // связываем правила с endpoint
            rules.forEach(r -> r.setEndpointEntity(e));
            return e;
        }

        public EndpointEntity buildAndSave() {
            EndpointEntity e = build();
            em.persist(e);
            em.flush();
            return e;
        }
    }

    /* =========================== CONSENT EVENT =========================== */

    public class ConsentEventBuilder {
        private final ConsentTestDataFactory factory;
        private String actionCode = "CREATE";
        private String system = "TEST";
        private UUID interactionId = UUID.randomUUID();
        private UUID consentUuid = UUID.randomUUID();
        private String consentStatusCode = "AWAITING_AUTHORIZATION";
        private LocalDateTime createdOn = LocalDateTime.now();
        private String statusCode = "ACCEPTED";

        private ConsentEventBuilder(ConsentTestDataFactory factory) {
            this.factory = factory;
        }

        public ConsentEventBuilder withActionCode(String action) {
            this.actionCode = action;
            return this;
        }

        public ConsentEventBuilder withSystem(String system) {
            this.system = system;
            return this;
        }

        public ConsentEventBuilder withInteractionId(UUID id) {
            this.interactionId = id;
            return this;
        }

        public ConsentEventBuilder withConsentUuid(UUID uuid) {
            this.consentUuid = uuid;
            return this;
        }

        public ConsentEventBuilder withConsentStatusCode(String status) {
            this.consentStatusCode = status;
            return this;
        }

        public ConsentEventBuilder withStatusCode(String status) {
            this.statusCode = status;
            return this;
        }

        public ConsentEventBuilder withCreatedOn(LocalDateTime time) {
            this.createdOn = time;
            return this;
        }

        public ConsentEventEntity build() {
            ConsentEventEntity e = new ConsentEventEntity();
            e.setActionCode(actionCode);
            e.setSystem(system);
            e.setInteractionId(interactionId);
            e.setConsentUuid(consentUuid);
            e.setConsentStatusCode(consentStatusCode);
            e.setCreatedOn(createdOn);
            e.setStatusCode(statusCode);
            return e;
        }

        public ConsentEventEntity buildAndSave() {
            ConsentEventEntity e = build();
            em.persist(e);
            em.flush();
            return e;
        }
    }

    /* =========================== HELPERS =========================== */

    /**
     * Создаёт полностью связный граф "Consent + PartyUser + Enduser + Endpoint + Event"
     * с минимальными дефолтными значениями. Удобно для быстрого старта теста.
     */
    public ConsentEntity createDefaultConsent() {
        PartyUserEntity partyUser = aPartyUser().buildAndSave();
        EnduserEntity enduser = anEnduser()
                .withEnduserName("Test Enduser")
                .withTaxNumber("1234567890")
                .buildAndSave();

        ConsentEntity consent = aConsent()
                .withPartyUser(partyUser)
                .withEnduser(enduser)
                .buildAndSave();

        anEndpoint()
                .withConsentUuid(consent.getConsentUuid())
                .withIpRule("192.168.1.1")
                .buildAndSave();

        anEvent()
                .withConsentUuid(consent.getConsentUuid())
                .withConsentStatusCode(consent.getStatusCode())
                .buildAndSave();

        return consent;
    }

    /**
     * Создаёт просроченный ACTIVE consent (для тестов expired-логики).
     */
    public ConsentEntity createExpiredActiveConsent() {
        ConsentEntity consent = createDefaultConsent();
        consent.setStatusCode("ACTIVE");
        consent.setExpiresOn(LocalDateTime.now().minusDays(1));
        em.merge(consent);
        em.flush();
        return consent;
    }

    /**
     * Создаёт consent с невалидным appIdentifier (для тестов 403).
     */
    public ConsentEntity createConsentWithDifferentAppId(String appId) {
        ConsentEntity consent = createDefaultConsent();
        consent.setApiKeyId(appId);
        consent.setClientId(null);
        em.merge(consent);
        em.flush();
        return consent;
    }
}