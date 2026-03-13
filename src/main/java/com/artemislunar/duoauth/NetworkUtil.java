package com.artemislunar.duoauth;

import com.velocitypowered.api.event.connection.PreLoginEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public final class NetworkUtil {

    private NetworkUtil() {
    }

    public static String extractIp(PreLoginEvent event) {
        SocketAddress remoteAddress = event.getConnection().getRemoteAddress();
        if (remoteAddress instanceof InetSocketAddress inetSocketAddress) {
            InetAddress address = inetSocketAddress.getAddress();
            if (address != null) {
                return address.getHostAddress();
            }
            return inetSocketAddress.getHostString();
        }
        return "";
    }
}

