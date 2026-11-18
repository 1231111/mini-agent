package com.miniagent.account.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class MembershipControllerPaymentCallbackTest {

    @Test
    void paymentCallbackFailsClosedUntilAChannelVerifierIsInstalled() {
        var response = new MembershipController().payCallback();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().getCode()).isEqualTo("MEMBER.03.02");
    }
}
