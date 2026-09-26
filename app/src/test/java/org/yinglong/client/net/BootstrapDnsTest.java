package org.yinglong.client.net;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class BootstrapDnsTest {
    @Test public void rejectsPoisonedAndPrivateBootstrapAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "0.0.0.0", "10.0.0.1", "192.168.1.1",
                "169.254.1.1", "100.64.0.1", "198.18.0.1", "224.0.0.1", "::1"}) {
            assertFalse(ip, BootstrapDns.isPublicV4(InetAddress.getByName(ip)));
        }
        assertTrue(BootstrapDns.isPublicV4(InetAddress.getByName("8.8.8.8")));
    }

    @Test public void scopesFallbackToOfficialBootstrapHosts() {
        assertTrue(BootstrapDns.isBootstrapHost("www.vpngate.net"));
        assertTrue(BootstrapDns.isBootstrapHost("download.vpngate.jp"));
        assertTrue(BootstrapDns.isBootstrapHost("x1.x2.servers.nat-traversal.softether-network.net."));
        assertTrue(BootstrapDns.isBootstrapHost("x1.x2.servers.nat-traversal.uxcom.jp."));
        assertFalse(BootstrapDns.isBootstrapHost("www.vpngate.net.evil.example"));
        assertFalse(BootstrapDns.isBootstrapHost("unrelated.example"));
        assertFalse(BootstrapDns.isBootstrapHost("219.100.37.96"));
    }
}
