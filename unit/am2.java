package com.example.consent.unit;

import com.example.consent.dto.AmRevision;
import com.example.consent.dto.AsyncApiDto;
import com.example.consent.feign.ApiManagerClient;
import com.example.consent.mapper.Wso2Mapper;
import com.example.consent.properties.ApiManagerProperties;
import com.example.consent.service.ApiManagerClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiManagerClientServiceUnitTest {

    @Mock
    private Wso2Mapper wso2Mapper;

    @Mock
    private ApiManagerClient apiManagerClient;

    @Mock
    private ApiManagerProperties amProperties;

    @InjectMocks
    private ApiManagerClientService apiManagerClientService;

    private static final String API_ID = "test-api-123";
    private static final String HOST = "https://am.example.com";

    @BeforeEach
    void setUp() {
        when(amProperties.getApiId()).thenReturn(API_ID);
        when(amProperties.getHost()).thenReturn(HOST);
    }

    @Test
    @DisplayName("getAsyncApi: делегирует вызов в ApiManagerClient с apiId из пропертей")
    void getAsyncApi_delegatesToClient() {
        AsyncApiDto expected = new AsyncApiDto();
        when(apiManagerClient.getAsyncApi(API_ID)).thenReturn(expected);

        AsyncApiDto result = apiManagerClientService.getAsyncApi();

        assertThat(result).isSameAs(expected);
        verify(apiManagerClient).getAsyncApi(API_ID);
    }

    @Test
    @DisplayName("updateTopics: маппит DTO в текст и делегирует вызов")
    void updateTopics_mapsAndDelegates() {
        AsyncApiDto request = new AsyncApiDto();
        String mappedText = "{\"channels\":{}}";
        AsyncApiDto expected = new AsyncApiDto();

        when(wso2Mapper.mapToText(request)).thenReturn(mappedText);
        when(apiManagerClient.updateTopics(API_ID, mappedText)).thenReturn(expected);

        AsyncApiDto result = apiManagerClientService.updateTopics(request);

        assertThat(result).isSameAs(expected);
        verify(wso2Mapper).mapToText(request);
        verify(apiManagerClient).updateTopics(API_ID, mappedText);
    }

    @Test
    @DisplayName("getRevisions: получает ревизии и маппит через wso2Mapper")
    void getRevisions_mapsList() {
        List<?> rawRevisions = List.of(new Object());
        List<AmRevision> expected = List.of(new AmRevision());

        when(apiManagerClient.getRevisions(API_ID)).thenReturn(rawRevisions);
        when(wso2Mapper.mapListRevisions(rawRevisions)).thenReturn(expected);

        List<AmRevision> result = apiManagerClientService.getRevisions();

        assertThat(result).isSameAs(expected);
        verify(apiManagerClient).getRevisions(API_ID);
        verify(wso2Mapper).mapListRevisions(rawRevisions);
    }

    @Test
    @DisplayName("removeRevision: делегирует удаление с apiId и revisionId")
    void removeRevision_delegatesToClient() {
        apiManagerClientService.removeRevision("rev-001");

        verify(apiManagerClient).removeRevision(API_ID, "rev-001");
    }

    @Test
    @DisplayName("postRevision: делегирует создание ревизии")
    void postRevision_delegatesToClient() {
        AmRevision revision = new AmRevision();
        AmRevision expected = new AmRevision();
        when(apiManagerClient.postRevision(API_ID, revision)).thenReturn(expected);

        AmRevision result = apiManagerClientService.postRevision(revision);

        assertThat(result).isSameAs(expected);
        verify(apiManagerClient).postRevision(API_ID, revision);
    }

    @Test
    @DisplayName("deployRevision: маппит хост и делегирует деплой")
    void deployRevision_mapsHostAndDelegates() {
        Object deployPayload = new Object();
        when(wso2Mapper.mapAmDeployRevision(HOST)).thenReturn(deployPayload);

        apiManagerClientService.deployRevision("rev-001");

        verify(wso2Mapper).mapAmDeployRevision(HOST);
        verify(apiManagerClient).deployRevision(API_ID, "rev-001", deployPayload);
    }
}