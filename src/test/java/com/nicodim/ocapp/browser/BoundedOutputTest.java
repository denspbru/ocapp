package com.nicodim.ocapp.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.nicodim.ocapp.support.ConversionException;
import org.junit.jupiter.api.Test;

class BoundedOutputTest {
    @Test void enforcesLimitForSingleAndBulkWrites() {
        BoundedByteArrayOutputStream output = new BoundedByteArrayOutputStream(3);
        output.write(1);
        output.write(new byte[]{2, 3}, 0, 2);
        assertThat(output.toByteArray()).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> output.write(4)).isInstanceOf(ConversionException.class)
            .extracting("code").isEqualTo("OUTPUT_TOO_LARGE");
        assertThatThrownBy(() -> new BoundedByteArrayOutputStream(1).write(new byte[]{1, 2}, 0, 2))
            .isInstanceOf(ConversionException.class);
        assertThatThrownBy(() -> new BoundedByteArrayOutputStream(1).write(new byte[]{1}, 0, -1))
            .isInstanceOf(ConversionException.class);
    }
}
