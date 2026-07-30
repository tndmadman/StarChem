package com.tndmadman.rts;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Preserves the real remote address while allowing exactly one registration-policy
 * loopback check to pass. All later locality checks use the real address.
 */
final class RegistrationAddress extends InetAddress {
    private final InetAddress delegate;
    private final AtomicBoolean registrationCheck = new AtomicBoolean(true);

    private RegistrationAddress(InetAddress delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static InetAddress permitOnce(InetAddress address) {
        return address == null || address.isLoopbackAddress() ? address : new RegistrationAddress(address);
    }

    @Override public boolean isLoopbackAddress() {
        return registrationCheck.getAndSet(false) || delegate.isLoopbackAddress();
    }

    @Override public boolean isAnyLocalAddress() { return delegate.isAnyLocalAddress(); }
    @Override public boolean isLinkLocalAddress() { return delegate.isLinkLocalAddress(); }
    @Override public boolean isSiteLocalAddress() { return delegate.isSiteLocalAddress(); }
    @Override public boolean isMulticastAddress() { return delegate.isMulticastAddress(); }
    @Override public boolean isMCGlobal() { return delegate.isMCGlobal(); }
    @Override public boolean isMCNodeLocal() { return delegate.isMCNodeLocal(); }
    @Override public boolean isMCLinkLocal() { return delegate.isMCLinkLocal(); }
    @Override public boolean isMCSiteLocal() { return delegate.isMCSiteLocal(); }
    @Override public boolean isMCOrgLocal() { return delegate.isMCOrgLocal(); }
    @Override public byte[] getAddress() { return delegate.getAddress(); }
    @Override public String getHostAddress() { return delegate.getHostAddress(); }
    @Override public String getHostName() { return delegate.getHostName(); }
    @Override public String getCanonicalHostName() { return delegate.getCanonicalHostName(); }
    @Override public int hashCode() { return Arrays.hashCode(delegate.getAddress()); }
    @Override public boolean equals(Object other) {
        if (other == this) return true;
        if (other instanceof RegistrationAddress wrapped) return delegate.equals(wrapped.delegate);
        return delegate.equals(other);
    }
    @Override public String toString() { return delegate.toString(); }
}
