package com.jellyfinserver.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Network helpers. */
public final class NetUtils {

    private NetUtils() {
    }

    /** Returns the IPv4 addresses of the device that other hosts can reach. */
    public static List<String> getLanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
            // Addresses stay empty, the UI falls back to the loopback URL.
        }
        return addresses;
    }

    public static String getLanUrl() {
        List<String> addresses = getLanAddresses();
        if (addresses.isEmpty()) {
            return null;
        }
        return "http://" + addresses.get(0) + ":" + AppConfig.PORT;
    }
}
