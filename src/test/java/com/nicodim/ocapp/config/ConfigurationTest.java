package com.nicodim.ocapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class ConfigurationTest {
    @Test void exposesAllConfigurationGroupsAndBeanAccessors() throws Exception {
        ConverterProperties root = new ConverterProperties();
        assertThat(root.getBrowser()).isNotNull();
        assertThat(root.getSecurity()).isNotNull();
        assertThat(root.getLimits()).isNotNull();
        assertThat(root.getPdf()).isNotNull();
        assertThat(root.getPptx()).isNotNull();
        exerciseBean(root.getBrowser());
        exerciseBean(root.getSecurity());
        exerciseBean(root.getLimits());
        exerciseBean(root.getPdf());
        exerciseBean(root.getPptx());
    }

    @Test void rejectsZeroOperationalTimeouts() {
        ConverterProperties properties = new ConverterProperties();
        properties.getLimits().setOperationTimeout(Duration.ZERO);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties)).isNotEmpty();
        }
    }

    @Test void createsNamedFixedSizeExecutor() throws Exception {
        ConverterProperties properties = new ConverterProperties(); properties.getLimits().setMaxConcurrent(1);
        ExecutorService executor = new ExecutionConfiguration().conversionExecutor(properties);
        try {
            assertThat(executor.submit(() -> Thread.currentThread().getName()).get()).startsWith("conversion-");
        } finally { executor.shutdownNow(); }
    }

    private static void exerciseBean(Object bean) throws Exception {
        for (Method setter : bean.getClass().getMethods()) {
            if (!setter.getName().startsWith("set") || setter.getParameterCount() != 1) continue;
            Object value = sample(setter.getParameterTypes()[0]);
            setter.invoke(bean, value);
            Method getter;
            String suffix = setter.getName().substring(3);
            try { getter = bean.getClass().getMethod("get" + suffix); }
            catch (NoSuchMethodException ex) { getter = bean.getClass().getMethod("is" + suffix); }
            assertThat(getter.invoke(bean)).isEqualTo(value);
        }
    }

    private static Object sample(Class<?> type) {
        if (type == String.class) return "value";
        if (type == boolean.class) return true;
        if (type == int.class) return 7;
        if (type == long.class) return 4096L;
        if (type == double.class) return 2.5d;
        if (type == Duration.class) return Duration.ofSeconds(2);
        if (type == List.class) return List.of("value");
        if (type.isEnum()) return type.getEnumConstants()[type.getEnumConstants().length - 1];
        throw new AssertionError("Unsupported property type " + type);
    }
}
