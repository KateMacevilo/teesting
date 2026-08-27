import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Component
public class CallbackUrlValidator {

    private static final UrlValidator URL_VALIDATOR = new UrlValidator(new String[]{"https"});
    private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+$");

    // Строгий IPv4: 0.0.0.0 – 255.255.255.255
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    // IPv6: только hex-символы и двоеточия (точная валидация — через InetAddress)
    private static final Pattern IPV6_PATTERN = Pattern.compile("^[0-9a-fA-F:]+$");

    private final SvcProperties svcProperties;
    private List<CidrMatcher> blacklistMatchers;

    @PostConstruct
    public void init() {
        this.blacklistMatchers = svcProperties.getBlacklistCidrs().stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .distinct()
                .map(CidrMatcher::new)
                .toList();
    }

    public boolean isValid(String callbackUrl) {
        if (callbackUrl == null
                || callbackUrl.contains(" ")
                || !URL_VALIDATOR.isValid(callbackUrl)) {
            return false;
        }

        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(callbackUrl)) {
            return false;
        }

        try {
            URI uri = new URI(callbackUrl);
            String host = uri.getHost();

            if (host == null || host.isBlank()) {
                return false;
            }

            if (!HOST_PATTERN.matcher(host).matches()) {
                return false;
            }

            // Проверка localhost (строковая, без DNS)
            if (isLocalhost(host)) {
                return false;
            }

            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > 65535)) {
                return false;
            }

            // --- ГЛАВНОЕ ИЗМЕНЕНИЕ ---
            // Проверяем blacklist/loopback/link-local ТОЛЬКО для явных IP-литералов.
            // Для доменов (example.com) DNS не вызывается.
            if (isIpLiteral(host)) {
                try {
                    InetAddress addr = InetAddress.getByName(host); // для IP — без DNS
                    if (addr.isLoopbackAddress()
                            || addr.isLinkLocalAddress()
                            || addr.isAnyLocalAddress()) {
                        return false;
                    }
                    for (CidrMatcher matcher : blacklistMatchers) {
                        if (matcher.matches(addr)) {
                            return false;
                        }
                    }
                } catch (UnknownHostException e) {
                    log.warn("Invalid IP literal format: {}", host);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            log.error("Unexpected error during validate callbackUrl: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean isLocalhost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || host.toLowerCase().endsWith(".localhost");
    }

    private boolean isIpLiteral(String host) {
        // IPv4 однозначно определяется регуляркой
        if (IPV4_PATTERN.matcher(host).matches()) {
            return true;
        }
        // IPv6: должны быть только hex и двоеточия (и хотя бы одно двоеточие)
        return IPV6_PATTERN.matcher(host).matches() && host.contains(":");
    }
}