@Component  // или просто класс, создаваемый вручную в тесте
public class ConsentTestDataFactory {

    private final ConsentDataService consentDataService;  // ваш сервис
    private final ConsentMapper consentMapper;

    public ConsentTestDataFactory(ConsentDataService consentDataService,
                                  ConsentMapper consentMapper) {
        this.consentDataService = consentDataService;
        this.consentMapper = consentMapper;
    }

    public ConsentEntity createActiveConsent() {
        // Собираем request как в реальном контроллере
        WebhookConsentRequest request = new WebhookConsentRequest();
        WebhookConsentData data = new WebhookConsentData();
        data.setCallbackUrl("https://test.com/callback");
        data.setAccessControl("PUBLIC");
        request.setData(data);

        // Мапим через ваш же маппер
        ConsentEntity consent = consentMapper.mapConsentEntity(
                request,
                UUID.randomUUID(),
                Map.of("USER_ID", "test-user"),
                UUID.randomUUID(),
                new ConsentRequestEventData("TEST", UUID.randomUUID(), "app-id", false)
        );
        consent.setStatusCode("ACTIVE");
        consent.setExpiresOn(LocalDateTime.now().plusDays(1));

        // Сохраняем через ваш сервис/репозиторий
        consentDataService.saveConsent(consent);
        return consent;
    }
}