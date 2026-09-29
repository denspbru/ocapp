package com.nicodim.ocapp.security;

import java.net.URI;

@FunctionalInterface
public interface NavigationResolver {
    URI resolve(URI initial);
}
