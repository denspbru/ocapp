package com.nicodim.ocapp.security;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
public interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
    static HostResolver system() { return InetAddress::getAllByName; }
}
