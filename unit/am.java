package com.example.consent.unit;

import com.example.consent.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Wso2ClientServiceImplUnitTest {

    @Mock
    private ApiManagerClientService amClient;

    @Mock
    private IdentityClientService isClient;

    @InjectMocks
    private Wso2ClientServiceImpl wso2Service;

    private static final String USER_ID = "user-123";
    private static final Map<String, String> CLAIMS = Map.of("SUBSCRIBER", "sub", "APPLICATION_UUID", "app", "KEY_TYPE", "API_KEY");

    @Test
    void getAsyncApi_delegatesToAmClient() {
        AsyncApiDto expected = new AsyncApiDto();
        when(amClient.getAsyncApi()).thenReturn(expected);

        AsyncApiDto result = wso2Service.getAsyncApi();

        assertThat(result).isSameAs(expected);
        verify(amClient, times(1)).getAsyncApi();
    }

    @Test
    void updateTopics_delegatesToAmClient() {
        AsyncApiDto dto = new AsyncApiDto();
        AsyncApiDto expected = new AsyncApiDto();
        when(amClient.updateTopics(dto)).thenReturn(expected);

        AsyncApiDto result = wso2Service.updateTopics(dto);

        assertThat(result).isSameAs(expected);
        verify(amClient).updateTopics(dto);
    }

    @Test
    void getRevisions_delegatesToAmClient() {
        List<AmRevision> expected = List.of(new AmRevision());
        when(amClient.getRevisions()).thenReturn(expected);

        List<AmRevision> result = wso2Service.getRevisions();

        assertThat(result).isSameAs(expected);
        verify(amClient).getRevisions();
    }

    @Test
    void removeRevision_delegatesToAmClient() {
        wso2Service.removeRevision("rev-001");
        verify(amClient).removeRevision("rev-001");
    }

    @Test
    void postRevision_delegatesToAmClient() {
        AmRevision rev = new AmRevision();
        AmRevision expected = new AmRevision();
        when(amClient.postRevision(rev)).thenReturn(expected);

        AmRevision result = wso2Service.postRevision(rev);

        assertThat(result).isSameAs(expected);
        verify(amClient).postRevision(rev);
    }

    @Test
    void deployRevision_delegatesToAmClient() {
        wso2Service.deployRevision("rev-001");
        verify(amClient).deployRevision("rev-001");
    }

    @Test
    void getAuthorizedApps_delegatesToIsClient() {
        when(isClient.getAuthorizedApps(USER_ID, CLAIMS)).thenReturn("client-001");

        String result = wso2Service.getAuthorizedApps(USER_ID, CLAIMS);

        assertThat(result).isEqualTo("client-001");
        verify(isClient).getAuthorizedApps(USER_ID, CLAIMS);
    }
}