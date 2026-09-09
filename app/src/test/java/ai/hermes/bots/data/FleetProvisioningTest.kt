package ai.hermes.bots.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FleetProvisioningTest {

    // --- normalizeBaseUrl (B8 save-path) --------------------------------------------------

    @Test
    fun `normalize prefixes scheme-less host`() {
        assertEquals("http://100.64.0.10:9300", FleetProvisioning.normalizeBaseUrl("100.64.0.10:9300"))
    }

    @Test
    fun `normalize trims whitespace and trailing slashes`() {
        assertEquals("http://m1.ts.net:9300", FleetProvisioning.normalizeBaseUrl("  http://m1.ts.net:9300//  "))
    }

    @Test
    fun `normalize keeps https and path roots`() {
        assertEquals("https://hermes.tail1234.ts.net", FleetProvisioning.normalizeBaseUrl("https://hermes.tail1234.ts.net/"))
    }

    @Test
    fun `normalize does not invent a default port`() {
        assertEquals("http://10.0.0.5", FleetProvisioning.normalizeBaseUrl("10.0.0.5"))
    }

    @Test
    fun `normalize keeps blank empty`() {
        assertEquals("", FleetProvisioning.normalizeBaseUrl("   "))
    }

    // --- parseAddGatewayUri (B1a deep link) -------------------------------------------------

    @Test
    fun `parses full token-mode link with encoded url`() {
        val params = FleetProvisioning.parseAddGatewayUri(
            "hermesbots://add-gateway?url=http%3A%2F%2F127.0.0.1%3A9119&token=dev-token-9119&name=Local",
        )!!
        assertEquals("http://127.0.0.1:9119", params.url)
        assertNull(params.username)
        assertEquals("dev-token-9119", params.token)
        assertNull(params.password)
        assertEquals("Local", params.label)
    }

    @Test
    fun `parses gated link user means basic with token as password`() {
        val params = FleetProvisioning.parseAddGatewayUri(
            "hermesbots://add-gateway?url=http%3A%2F%2F100.64.0.10%3A9300&user=user&token=REDACTED-PASSWORD&name=m1",
        )!!
        assertEquals("http://100.64.0.10:9300", params.url)
        assertEquals("user", params.username)
        assertEquals("REDACTED-PASSWORD", params.password)
        assertNull(params.token)
        assertEquals("m1", params.label)
    }

    @Test
    fun `name is optional and scheme-less url gets http`() {
        val params = FleetProvisioning.parseAddGatewayUri(
            "hermesbots://add-gateway?url=100.64.0.11%3A9300&user=user&token=pw",
        )!!
        assertEquals("http://100.64.0.11:9300", params.url)
        assertNull(params.label)
    }

    @Test
    fun `plus in secret decodes literally not as space`() {
        val params = FleetProvisioning.parseAddGatewayUri(
            "hermesbots://add-gateway?url=http%3A%2F%2Fm%3A9300&user=w&token=ab%2Bcd",
        )!!
        assertEquals("ab+cd", params.password)
    }

    @Test
    fun `missing url rejects`() {
        assertNull(FleetProvisioning.parseAddGatewayUri("hermesbots://add-gateway?user=w&token=pw"))
    }

    @Test
    fun `missing query rejects`() {
        assertNull(FleetProvisioning.parseAddGatewayUri("hermesbots://add-gateway"))
    }

    @Test
    fun `wrong scheme or host rejects`() {
        assertNull(FleetProvisioning.parseAddGatewayUri("https://add-gateway?url=http%3A%2F%2Fx&token=t"))
        assertNull(FleetProvisioning.parseAddGatewayUri("hermesbots://other-host?url=http%3A%2F%2Fx&token=t"))
        assertNull(FleetProvisioning.parseAddGatewayUri("not a uri"))
    }

    // --- addGatewayUri builder (mirrors the script's --qr output) ---------------------------

    @Test
    fun `builder and parser round-trip`() {
        val uri = FleetProvisioning.addGatewayUri(
            url = "100.64.0.10:9300/",
            username = "user",
            secret = "s3cr3t+/=",
            label = "m1 gate",
        )
        val params = FleetProvisioning.parseAddGatewayUri(uri)!!
        assertEquals("http://100.64.0.10:9300", params.url)
        assertEquals("user", params.username)
        assertEquals("s3cr3t+/=", params.password)
        assertEquals("m1 gate", params.label)
    }

    @Test
    fun `builder omits user for token mode`() {
        val params = FleetProvisioning.parseAddGatewayUri(
            FleetProvisioning.addGatewayUri("http://127.0.0.1:9119", username = null, secret = "tok", label = null),
        )!!
        assertNull(params.username)
        assertEquals("tok", params.token)
    }
}
