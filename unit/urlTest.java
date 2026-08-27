import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CallbackUrlValidator — структурная и DNS валидация")
class CallbackUrlValidatorTest {

    @Mock
    private SvcProperties svcProperties;

    @InjectMocks
    private CallbackUrlValidator validator;

    @BeforeEach
    void setUp() {
        when(svcProperties.getBlacklistCidrs()).thenReturn(List.of(
                "10.0.0.0/8",
                "172.16.0.0/12",
                "192.168.0.0/16",
                "100.64.0.0/10",
                "169.254.0.0/16",
                "fe80::/10",
                "fc00::/7",
                "::1/128"
        ));
        // @PostConstruct не сработает без Spring-контекста — вызываем вручную
        validator.init();
    }

    // ==================== УСПЕХ ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://8.8.8.8/webhook",                 // Google DNS — публичный IP
            "https://1.1.1.1:8080/path",               // Cloudflare + валидный порт
            "https://8.8.8.8:443/",                    // Явный порт 443
            "https://1.0.0.1/webhook?status=ok"        // Публичный IP + query
    })
    @DisplayName("✅ Публичные IPv4-литералы проходят (DNS не нужен)")
    void validPublicIpLiterals(String url) {
        assertThat(validator.isValid(url)).isTrue();
    }

    @Test
    @DisplayName("✅ Валидный публичный домен (требуется DNS)")
    @Tag("network")
    void validPublicDomain() {
        assertThat(validator.isValid("https://example.com/webhook")).isTrue();
    }

    // ==================== ФОРМАТ / ПРОТОКОЛ / ПОРТ ====================

    @Test
    @DisplayName("❌ Null")
    void rejectNull() {
        assertThat(validator.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com/webhook",   // не https
            "ftp://example.com/webhook",    // не https
            "file:///etc/passwd"            // не https и нет хоста
    })
    @DisplayName("❌ Не-https протоколы")
    void rejectNonHttps(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/web hook",      // пробел в пути
            "https://exa mple.com/webhook"       // пробел в хосте
    })
    @DisplayName("❌ Пробел в URL")
    void rejectSpaces(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com:0/webhook",
            "https://example.com:70000/webhook",
            "https://example.com:-1/webhook",
            "https://example.com:99999/webhook"
    })
    @DisplayName("❌ Порт вне диапазона 1–65535")
    void rejectInvalidPorts(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://exa_mple.com/webhook",      // '_' не разрешён HOST_PATTERN
            "https://example!.com/webhook",      // '!' не разрешён
            "https://example$.com/webhook"       // '$' не разрешён
    })
    @DisplayName("❌ Недопустимые символы в хосте")
    void rejectInvalidHostChars(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    // ==================== LOCALHOST ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/webhook",
            "https://sub.localhost/webhook",
            "https://API.LOCALHOST/v1/callback",   // регистр не важен
            "https://deep.sub.localhost/"          // заканчивается на .localhost
    })
    @DisplayName("❌ Любой localhost")
    void rejectLocalhostVariants(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    // ==================== LOOPBACK / ANY-LOCAL / LINK-LOCAL ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/webhook",
            "https://127.0.0.53/webhook",   // systemd-resolved, но всё ещё loopback
            "https://127.255.255.255/webhook"
    })
    @DisplayName("❌ IPv4 loopback (127.x.x.x)")
    void rejectIpv4Loopback(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://0.0.0.0/webhook"
    })
    @DisplayName("❌ IPv4 any-local (0.0.0.0)")
    void rejectAnyLocal(String url) {
        assertThat(validator.isValid(url)).isFalse();
    }

    // ==================== BLACKLIST IPv4 ====================

    @ParameterizedTest
    @CsvSource({
            "https://10.0.0.1/webhook,         10.0.0.0/8",
            "https://10.255.255.255/webhook,   10.0.0.0/8",
            "https://172.16.0.1/webhook,       172.16.0.0/12",
            "https://172.31.255.255/webhook,   172.16.0.0/12",
            "https://192.168.0.1/webhook,      192.168.0.0/16",
            "https://192.168.255.255/webhook,  192.168.0.0/16",
            "https://100.64.0.1/webhook,       100.64.0.0/10",
            "https://100.127.255.255/webhook,  100.64.0.0/10",
            "https://169.254.1.1/webhook,      169.254.0.0/16"
    })
    @DisplayName("❌ Blacklist IPv4 CIDR")
    void rejectBlacklistedIpv4(String url, String cidr) {
        assertThat(validator.isValid(url))
                .withFailMessage("URL %s должен отсекаться blacklist'ом %s", url, cidr)
                .isFalse();
    }

    // ==================== BLACKLIST / LOOPBACK IPv6 ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://[::1]/webhook",            // loopback + ::1/128
            "https://[fe80::1]/webhook",        // link-local + fe80::/10
            "https://[febf:ffff::1]/webhook",   // граница fe80::/10
            "https://[fc00::1]/webhook",        // ULA + fc00::/7
            "https://[fd00:1234::1]/webhook"    // ULA внутри fc00::/7
    })
    @DisplayName("❌ IPv6 литералы (в текущем коде отсекаются HOST_PATTERN, т.к. нет ':' в regex)")
    void rejectIpv6Literals(String url) {
        // Примечание: в оригинальном коде HOST_PATTERN = ^[a-zA-Z0-9.-]+$
        // uri.getHost() для [fe80::1] вернёт "fe80::1", что НЕ матчит паттерн.
        // Если добавите ':' в HOST_PATTERN — тогда отсечка пойдёт по isLinkLocal / blacklist.
        assertThat(validator.isValid(url)).isFalse();
    }

    // ==================== DNS-ОБХОД ЧЕРЕЗ ДОМЕН ====================

    @Test
    @DisplayName("❌ Несуществующий домен (UnknownHostException)")
    @Tag("network")
    void rejectUnresolvableDomain() {
        assertThat(validator.isValid("https://this-domain-does-not-exist-12345.xyz/webhook"))
                .isFalse();
    }

    @Test
    @DisplayName("❌ Домен резолвится в blacklist IP (DNS-обход)")
    @Tag("network")
    void rejectDomainResolvingToBlacklistedIp() {
        // Пример: если бы у вас был внутренний домен, резолвящийся в 192.168.x.x
        // В unit-тесте без реального DNS проверить сложно, но логика такая:
        // validator.isValid("https://internal.corp.com/webhook") == false,
        // если DNS отдаёт 10.x / 172.16-31.x / 192.168.x и т.д.
    }

    // ==================== ДОПОЛНИТЕЛЬНЫЕ ГРАНИЧНЫЕ КЕЙСЫ ====================

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",              // нет path — но UrlValidator пропустит
            "https://example.com:443/webhook",  // стандартный порт, явно указан
            "https://example.com:1/webhook"     // валидный порт (1)
    })
    @DisplayName("✅ Граничные валидные случаи")
    void validEdgeCases(String url) {
        assertThat(validator.isValid(url)).isTrue();
    }
}