package ru.questionhacker.trainer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentclientprotocol.sdk.client.AcpAsyncClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AcpGatewayCloseTest {

    @Test
    void gracefulCloseFailureDoesNotEscapeAndStillForcesClose() {
        var client = mock(AcpAsyncClient.class);
        when(client.closeGracefully()).thenReturn(Mono.error(new IllegalStateException("graceful failed")));

        assertThatCode(() -> AcpGateway.close(client)).doesNotThrowAnyException();

        verify(client).close();
    }

    @Test
    void forcedCloseFailureDoesNotEscape() {
        var client = mock(AcpAsyncClient.class);
        when(client.closeGracefully()).thenReturn(Mono.empty());
        doThrow(new IllegalStateException("forced close failed")).when(client).close();

        assertThatCode(() -> AcpGateway.close(client)).doesNotThrowAnyException();
    }
}
