package com.miniagent.memory.contract;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceTokenTest {

    static KeyPair newKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        return gen.generateKeyPair();
    }

    @Test
    void roundTripCarriesPrincipalAndSupportsKeyRotation() throws Exception {
        KeyPair old = newKey();
        KeyPair current = newKey();
        String encodedPrivate = Base64.getEncoder().encodeToString(current.getPrivate().getEncoded());
        String encodedPublic = Base64.getEncoder().encodeToString(current.getPublic().getEncoded());
        ServiceToken.Principal principal =
                new ServiceToken.Principal("app", ServiceToken.ROLE_APP, "t1", 42L, "p1");

        String token = ServiceToken.sign(ServiceToken.privateKey(encodedPrivate), principal,
                Duration.ofMinutes(1));
        ServiceToken.Principal verified = ServiceToken.verify(token, Map.of("app",
                List.of(old.getPublic(), ServiceToken.publicKey(encodedPublic))));

        assertEquals(principal, verified);
    }

    @Test
    void rejectsWrongKeyUnknownIssuerAndExpiry() throws Exception {
        KeyPair signer = newKey();
        KeyPair other = newKey();
        ServiceToken.Principal principal =
                new ServiceToken.Principal("eval-worker", ServiceToken.ROLE_EVAL, null, null, null);
        String token = ServiceToken.sign(signer.getPrivate(), principal, Duration.ofMinutes(1));

        assertThrows(IllegalArgumentException.class, () -> ServiceToken.verify(token,
                Map.of("eval-worker", List.of(other.getPublic()))));
        assertThrows(IllegalArgumentException.class, () -> ServiceToken.verify(token,
                Map.of("app", List.of(signer.getPublic()))));
        String expired = ServiceToken.sign(signer.getPrivate(), principal, Duration.ofMinutes(-5));
        assertThrows(IllegalArgumentException.class, () -> ServiceToken.verify(expired,
                Map.of("eval-worker", List.of(signer.getPublic()))));
        assertThrows(IllegalArgumentException.class,
                () -> ServiceToken.verify("garbage", Map.of()));
    }
}
