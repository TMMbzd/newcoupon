package com.newcoupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.newcoupon.application.dto.ReceiveAsyncResult;
import com.newcoupon.application.dto.ReceiveRequest;
import com.newcoupon.application.service.ReceiveAppService;
import com.newcoupon.application.service.ReceiveAsyncAppService;
import com.newcoupon.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 异步受理的 HTTP 契约：202 只表示已受理，body 带 requestId/taskId/status。 */
class ReceiveControllerAsyncTest {

    @Test
    @DisplayName("POST /receive-async 返回 HTTP 202 和 ACCEPTED")
    void receiveAsyncReturns202() {
        ReceiveAsyncAppService asyncService = mock(ReceiveAsyncAppService.class);
        when(asyncService.receive(any())).thenReturn(
                new ReceiveAsyncResult("req-1", 123L, "ACCEPTED"));
        ReceiveController controller = new ReceiveController(mock(ReceiveAppService.class), asyncService);

        ResponseEntity<Result<ReceiveAsyncResult>> response = controller.receiveAsync(
                new ReceiveRequest("req-1", 1001L, 2001L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().requestId()).isEqualTo("req-1");
        assertThat(response.getBody().getData().taskId()).isEqualTo(123L);
        assertThat(response.getBody().getData().status()).isEqualTo("ACCEPTED");
    }
}
