package com.example.consent.unit;

import com.example.consent.dto.IdentityResponse;
import com.example.consent.exception.OBException;
import com.example.consent.feign.IdentityClient;
import com.example.consent.properties.IdentityServerProperties;
import com.example.consent.service.IdentityClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityClientServiceUnitTest {

    @Mock
    private IdentityClient identityClient;

    @Mock
    private IdentityServerProperties identityProperties;

    @InjectMocks
    private IdentityClientService identityClientService;

    private static final String USER_ID = "user-123";
    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "test-pass";

    @BeforeEach
    void setUp() {
        IdentityServerProperties.Auth auth = new IdentityServerProperties.Auth();
        auth.setUsername(USERNAME);
        auth.setPassword(PASSWORD);
        when(identityProperties.getAuth()).thenReturn(auth);
    }

    private Map<String, String> buildClaims() {
        return Map.of(
                "SUBSCRIBER", "sub-001",
                "APPLICATION_UUID", "app-uuid-123",
                "KEY_TYPE", "API_KEY"
        );
    }

    private String expectedBasicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes());
    }

    @Test
    @DisplayName("getAuthorizedApps: находит clientId по совпадению имени")
    void getAuthorizedApps_success() {
        String expectedName = "sub-001_app-uuid-123_API_KEY";
        IdentityResponse response = new IdentityResponse();
        response.setName(expectedName);
        response.setClientId("client-abc-123");

        when(identityClient.getAuthorizedApps(USER_ID, expectedBasicAuth()))
                .thenReturn(List.of(response));

        String result = identityClientService.getAuthorizedApps(USER_ID, buildClaims());

        assertThat(result).isEqualTo("client-abc-123");
        verify(identityClient).getAuthorizedApps(USER_ID, expectedBasicAuth());
    }

    @Test
    @DisplayName("getAuthorizedApps: client not found -> OBException 400")
    void getAuthorizedApps_clientNotFound_throwsOBException400() {
        IdentityResponse response = new IdentityResponse();
        response.setName("other-name");
        response.setClientId("other-id");

        when(identityClient.getAuthorizedApps(USER_ID, expectedBasicAuth()))
                .thenReturn(List.of(response));

        assertThatThrownBy(() -> identityClientService.getAuthorizedApps(USER_ID, buildClaims()))
                .isInstanceOf(OBException.class)
                .satisfies(ex -> {
                    OBException ob = (OBException) ex;
                    assertThat(ob.getStatusCode()).isEqualTo(400);
                });
    }

    @Test
    @DisplayName("getAuthorizedApps: пустой список от IS -> OBException 400")
    void getAuthorizedApps_emptyList_throwsOBException400() {
        when(identityClient.getAuthorizedApps(USER_ID, expectedBasicAuth()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> identityClientService.getAuthorizedApps(USER_ID, buildClaims()))
                .isInstanceOf(OBException.class);
    }

    @Test
    @DisplayName("getAuthorizedApps: SuiteException 4xx -> OBException 500")
    void getAuthorizedApps_suiteException4xx_throwsOBException500() {
        SuiteException suiteEx = mock(SuiteException.class);
        SuiteError suiteError = mock(SuiteError.class);
        Status status = mock(Status.class);

        when(suiteEx.getSuiteError()).thenReturn(suiteError);
        when(suiteError.getStatus()).thenReturn(status);
        when(status.getStatusCode()).thenReturn(404); // 4xx

        when(identityClient.getAuthorizedApps(any(), any())).thenThrow(suiteEx);

        assertThatThrownBy(() -> identityClientService.getAuthorizedApps(USER_ID, buildClaims()))
                .isInstanceOf(OBException.class)
                .satisfies(ex -> {
                    OBException ob = (OBException) ex;
                    assertThat(ob.getStatusCode()).isEqualTo(500);
                });
    }

    @Test
    @DisplayName("getAuthorizedApps: SuiteException 5xx -> пробрасывает SuiteException")
    void getAuthorizedApps_suiteException5xx_rethrows() {
        SuiteException suiteEx = mock(SuiteException.class);
        SuiteError suiteError = mock(SuiteError.class);
        Status status = mock(Status.class);

        when(suiteEx.getSuiteError()).thenReturn(suiteError);
        when(suiteError.getStatus()).thenReturn(status);
        when(status.getStatusCode()).thenReturn(503); // 5xx

        when(identityClient.getAuthorizedApps(any(), any())).thenThrow(suiteEx);

        assertThatThrownBy(() -> identityClientService.getAuthorizedApps(USER_ID, buildClaims()))
                .isInstanceOf(SuiteException.class);
    }

    @Test
    @DisplayName("getAuthorizedApps: generic Exception -> SuiteException")
    void getAuthorizedApps_genericException_throwsSuiteException() {
        when(identityClient.getAuthorizedApps(any(), any()))
                .thenThrow(new RuntimeException("connection timeout"));

        assertThatThrownBy(() -> identityClientService.getAuthorizedApps(USER_ID, buildClaims()))
                .isInstanceOf(SuiteException.class)
                .hasMessageContaining("connection timeout");
    }

    @Test
    @DisplayName("getAuthorizedApps: OBException из identityClient -> пробрасывает как есть")
    void getAuthorizedApps_obException_rethrows() {
        OBException obEx = new OBException(403, "Forbidden");
        when(identityClient.getAuthorizedApps(any(), any())).thenThrow(obEx);

        assertThatThrownBy(() -> identityClientService.getAuthorizedApps(USER_ID, buildClaims()))
                .isSameAs(obEx);
    }
}