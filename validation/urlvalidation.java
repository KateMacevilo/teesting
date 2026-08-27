import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.validator.routines.UrlValidator;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
@Component
public class CallbackUrlValidator {

    private static final UrlValidator URL_VALIDATOR = new UrlValidator(new String[]{"https"});
    // Добавлено двоеточие для IPv6 (например, ::1, 2001:db8::1)
    private static final Pattern HOST_PATTERN = Pattern.compile("^[a-zA-Z0-9.:-]+$");

    private final SvcProperties svcProperties;
    private List<CidrMatcher> blacklistMatchers;

    @PostConstruct
    public void init() {
        this.blacklistMatchers = svcProperties.getBlacklistCidrs().stream()
                .filter(cidr -> cidr != null && !cidr.isBlank())
                .distinct()
                .peek(cidr -> {
                    // Падаем при старте, если CIDR в конфиге кривой
                    if (!isValidCidr(cidr)) {
                        throw new IllegalArgumentException("Invalid blacklist CIDR in config: " + cidr);
                    }
                })
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

            if ("localhost".equalsIgnoreCase(host)
                    || host.toLowerCase().endsWith(".localhost")) {
                return false;
            }

            int port = uri.getPort();
            if (port != -1 && (port < 1 || port > 65535)) {
                return false;
            }

            // DNS lookup с таймаутом 3 секунды, чтобы не висеть вечно
            InetAddress[] addresses = resolveWithTimeout(host, 3, TimeUnit.SECONDS);
            List<InetAddress> uniqueAddresses = Arrays.stream(addresses)
                    .distinct()
                    .toList();

            if (uniqueAddresses.isEmpty()) {
                return false;
            }

            for (InetAddress ip : uniqueAddresses) {
                if (ip.isLoopbackAddress()
                        || ip.isLinkLocalAddress()
                        || ip.isAnyLocalAddress()) {
                    return false;
                }

                for (CidrMatcher matcher : blacklistMatchers) {
                    if (matcher.matches(ip)) {
                        // Early return: хотя бы один IP попал в blacklist → URL невалиден
                        return false;
                    }
                }
            }

            return true;

        } catch (UnknownHostException e) {
            log.warn("DNS lookup failed for callbackUrl '{}': {}", callbackUrl, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error validating callbackUrl '{}': {}", callbackUrl, e.getMessage(), e);
            return false;
        }
    }

    /**
     * InetAddress.getAllByName() блокирует поток на неопределённое время.
     * Оборачиваем в CompletableFuture с таймаутом.
     */
    private InetAddress[] resolveWithTimeout(String host, long timeout, TimeUnit unit)
            throws UnknownHostException {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return InetAddress.getAllByName(host);
                } catch (UnknownHostException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }).orTimeout(timeout, unit).join();
        } catch (java.util.concurrent.CompletionException e) {
            if (e.getCause() instanceof UnknownHostException) {
                throw (UnknownHostException) e.getCause();
            }
            throw e;
        }
    }

    private boolean isValidCidr(String cidr) {
        if (cidr == null || !cidr.contains("/")) {
            return false;
        }
        String[] parts = cidr.split("/");
        if (parts.length != 2) {
            return false;
        }
        try {
            int prefix = Integer.parseInt(parts[1]);
            // IPv4: 0–32, IPv6: 0–128
            // Если по требованиям строго /24 — добавьте: if (prefix != 24) return false;
            return prefix >= 0 && prefix <= 128;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}