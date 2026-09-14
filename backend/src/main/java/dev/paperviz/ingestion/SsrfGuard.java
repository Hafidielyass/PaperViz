package dev.paperviz.ingestion;

import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses internal network targets so a crafted link cannot turn the backend
 * into an SSRF pivot against the Docker network, the host loopback, or cloud
 * metadata endpoints.
 *
 * Literal IPv4 is decided arithmetically (no DNS involved). Hostnames resolve
 * to every address so a host that is fine on one v4 and evil on another v6 is
 * still refused. The lingering DNS-rebinding window (re-resolve between check
 * and connect) is accepted for v1.
 */
@Component
public class SsrfGuard {

    /**
     * @throws IngestionException when the target is not a routable public address
     */
    public void check(String host) {
        if (host == null || host.isBlank()) {
            throw new IngestionException("That link has no host.");
        }

        if (host.contains(":")) {
            // IPv6 literal (or bracketed, as URL.getHost() returns it).
            InetAddress address = resolveLiteralIPv6(stripBrackets(host));
            if (isUnsafe(address)) {
                throw new IngestionException("That link targets an internal address.");
            }
            return;
        }

        Long ipv4 = parseIpv4(host);
        if (ipv4 != null) {
            if (isUnsafeIpv4(ipv4)) {
                throw new IngestionException("That link targets an internal address.");
            }
            return;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IngestionException("Could not resolve the host in that link.");
        }
        for (InetAddress address : addresses) {
            if (isUnsafe(address)) {
                throw new IngestionException("That link targets an internal address.");
            }
        }
    }

    private InetAddress resolveLiteralIPv6(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IngestionException("That link's address could not be parsed.");
        }
    }

    private boolean isUnsafe(InetAddress address) {
        if (address.isAnyLocalAddress()      // 0.0.0.0, ::
                || address.isLoopbackAddress() // 127/8, ::1
                || address.isLinkLocalAddress()// 169.254/16, fe80::/10
                || address.isSiteLocalAddress()// 10/8, 172.16/12, 192.168/16, fc00::/7
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            long v = ((bytes[0] & 0xFFL) << 24)
                    | ((bytes[1] & 0xFFL) << 16)
                    | ((bytes[2] & 0xFFL) << 8)
                    | (bytes[3] & 0xFFL);
            return isUnsafeIpv4(v);
        }
        if (bytes.length == 16) {
            // Unique-local fc00::/7. InetAddress.isSiteLocalAddress() only reports
            // the deprecated fec0::/10, so ULA targets would otherwise slip through.
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    private static boolean isUnsafeIpv4(long v) {
        return (v & 0xFF000000L) == 0            // 0.0.0.0/8
                || (v & 0xFF000000L) == 0x0A000000L     // 10.0.0.0/8
                || (v & 0xFFC00000L) == 0x64400000L     // 100.64.0.0/10 CGNAT
                || (v & 0xFF000000L) == 0x7F000000L     // 127.0.0.0/8 loopback
                || (v & 0xFFFF0000L) == 0xA9FE0000L     // 169.254.0.0/16 link-local
                || (v & 0xFFF00000L) == 0xAC100000L     // 172.16.0.0/12
                || (v & 0xFFFF0000L) == 0xC0A80000L     // 192.168.0.0/16
                || (v & 0xF0000000L) == 0xE0000000L     // 224.0.0.0/4 multicast
                || (v & 0xF0000000L) == 0xF0000000L;    // 240.0.0.0/4 reserved
    }

    /** Parses a dotted quad as unsigned arithmetic; null at the first bad octet or count. */
    private static Long parseIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        long value = 0;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return null;
            }
            try {
                int part = Integer.parseInt(octet);
                if (part > 255) {
                    return null;
                }
                value = (value << 8) | part;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return value;
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }
}