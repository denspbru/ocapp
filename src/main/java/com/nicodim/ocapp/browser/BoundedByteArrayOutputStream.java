package com.nicodim.ocapp.browser;

import com.nicodim.ocapp.support.ConversionException;
import java.io.ByteArrayOutputStream;
import org.springframework.http.HttpStatus;

public final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
    private final long maximum;

    public BoundedByteArrayOutputStream(long maximum) {
        super((int) Math.min(maximum, 8192));
        this.maximum = maximum;
    }

    @Override public synchronized void write(int value) {
        ensureCapacityFor(1);
        super.write(value);
    }

    @Override public synchronized void write(byte[] value, int offset, int length) {
        ensureCapacityFor(length);
        super.write(value, offset, length);
    }

    private void ensureCapacityFor(int additional) {
        if (additional < 0 || count > maximum - additional) {
            throw new ConversionException(HttpStatus.PAYLOAD_TOO_LARGE, "OUTPUT_TOO_LARGE", "Generated document exceeds the configured limit");
        }
    }
}
