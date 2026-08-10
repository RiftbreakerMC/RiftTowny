package net.riftbreaker.rifttowny.integrations;

import net.riftbreaker.rifttowny.api.capability.Capability;
import net.riftbreaker.rifttowny.api.capability.CapabilityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCapabilityRegistryTest {

    private final List<String> log = new ArrayList<>();
    private final DefaultCapabilityRegistry registry = new DefaultCapabilityRegistry(log::add);

    private static Predicate<String> installed(final String... names) {
        final Set<String> present = Set.of(names);
        return present::contains;
    }

    private static IntegrationAdapter adapter(final Capability capability, final Binder binder) {
        return new IntegrationAdapter() {
            @Override
            public Capability capability() {
                return capability;
            }

            @Override
            public Object bind() throws Exception {
                return binder.bind();
            }

            @Override
            public String describe(final Object bound) {
                return "version 1.2.3";
            }
        };
    }

    @FunctionalInterface
    private interface Binder {
        Object bind() throws Exception;
    }

    @Test
    @DisplayName("every capability starts absent, so nothing is claimed before it is proven")
    void everythingStartsAbsent() {
        assertThat(registry.statuses()).hasSize(Capability.values().length);
        assertThat(registry.statuses()).allSatisfy(status ->
                assertThat(status.state()).isEqualTo(CapabilityState.ABSENT));
    }

    @Test
    @DisplayName("a missing plugin is absent, not failed, and is not logged as a problem")
    void missingPluginIsAbsent() {
        final boolean bound = registry.register(
                adapter(Capability.ECONOMY_RIFTECO, () -> "service"), installed());

        assertThat(bound).isFalse();
        assertThat(registry.status(Capability.ECONOMY_RIFTECO).state()).isEqualTo(CapabilityState.ABSENT);
        assertThat(log).isEmpty();
    }

    @Test
    @DisplayName("a successful bind is active and exposes its service")
    void successfulBindIsActive() {
        final Object service = new Object();

        final boolean bound = registry.register(
                adapter(Capability.ECONOMY_RIFTECO, () -> service), installed("RiftEco"));

        assertThat(bound).isTrue();
        assertThat(registry.isActive(Capability.ECONOMY_RIFTECO)).isTrue();
        assertThat(registry.status(Capability.ECONOMY_RIFTECO).detail()).isEqualTo("version 1.2.3");
        assertThat(registry.service(Capability.ECONOMY_RIFTECO, Object.class)).contains(service);
    }

    @Test
    @DisplayName("a plugin that is present but hands back nothing is unverified, never active")
    void nullServiceIsUnverified() {
        registry.register(adapter(Capability.CHAT_RIFTCHAT, () -> null), installed("RiftChat"));

        assertThat(registry.status(Capability.CHAT_RIFTCHAT).state())
                .isEqualTo(CapabilityState.PRESENT_UNVERIFIED);
        assertThat(registry.isActive(Capability.CHAT_RIFTCHAT)).isFalse();
        assertThat(registry.service(Capability.CHAT_RIFTCHAT, Object.class)).isEmpty();
    }

    @Test
    @DisplayName("a version mismatch throwing NoSuchMethodError is contained, and its cause is recorded")
    void linkageErrorIsContained() {
        final boolean bound = registry.register(
                adapter(Capability.SHOPS_RIFTSHOP, () -> {
                    throw new NoSuchMethodError("mc.riftbreaker.shop.api.ShopService.listings()");
                }),
                installed("RiftShop"));

        assertThat(bound).isFalse();
        assertThat(registry.status(Capability.SHOPS_RIFTSHOP).state()).isEqualTo(CapabilityState.FAILED);
        assertThat(registry.status(Capability.SHOPS_RIFTSHOP).detail())
                .contains("NoSuchMethodError")
                .contains("listings()");
        assertThat(registry.status(Capability.SHOPS_RIFTSHOP).failureCause()).isPresent();
        assertThat(log).anyMatch(line -> line.contains("Only features that need it are affected"));
    }

    @Test
    @DisplayName("one failing adapter does not stop the next one binding")
    void oneFailureDoesNotAffectOthers() {
        registry.register(
                adapter(Capability.SHOPS_RIFTSHOP, () -> {
                    throw new NoClassDefFoundError("mc/riftbreaker/shop/api/ShopService");
                }),
                installed("RiftShop", "RiftEco"));
        registry.register(adapter(Capability.ECONOMY_RIFTECO, Object::new), installed("RiftShop", "RiftEco"));

        assertThat(registry.status(Capability.SHOPS_RIFTSHOP).state()).isEqualTo(CapabilityState.FAILED);
        assertThat(registry.isActive(Capability.ECONOMY_RIFTECO)).isTrue();
    }

    @Test
    @DisplayName("a blocked capability is distinguishable from a failed one")
    void blockedIsNotFailed() {
        registry.markBlocked(Capability.DISCORD_CHANNEL_PROVISIONING,
                "VelocitySrv has no channel-creation API");

        assertThat(registry.status(Capability.DISCORD_CHANNEL_PROVISIONING).state())
                .isEqualTo(CapabilityState.BLOCKED);
        assertThat(registry.status(Capability.DISCORD_CHANNEL_PROVISIONING).failureCause()).isEmpty();
        assertThat(registry.isActive(Capability.DISCORD_CHANNEL_PROVISIONING)).isFalse();
    }

    @Test
    @DisplayName("the service is not handed out for a capability that is not active")
    void serviceRequiresActive() {
        registry.markDisabled(Capability.SKILLS_MCMMO);

        assertThat(registry.service(Capability.SKILLS_MCMMO, Object.class)).isEmpty();
    }

    @Test
    @DisplayName("a service of the wrong type is refused rather than mis-cast at the call site")
    void serviceTypeIsChecked() {
        registry.register(adapter(Capability.AUDIT_RIFTLOGGER, () -> "a string"), installed("RiftLogger"));

        assertThat(registry.service(Capability.AUDIT_RIFTLOGGER, String.class)).contains("a string");
        assertThat(registry.service(Capability.AUDIT_RIFTLOGGER, Integer.class)).isEmpty();
    }

    @Test
    @DisplayName("the summary puts failures first, so the log line that matters is not buried")
    void summaryPutsProblemsFirst() {
        registry.register(adapter(Capability.ECONOMY_RIFTECO, Object::new), installed("RiftEco"));
        registry.markBlocked(Capability.DISCORD_CHANNEL_PROVISIONING, "no upstream API");
        registry.register(
                adapter(Capability.SHOPS_RIFTSHOP, () -> {
                    throw new NoSuchMethodError("boom");
                }),
                installed("RiftShop"));

        final List<String> summary = registry.summary();

        assertThat(summary.getFirst()).contains("SHOPS_RIFTSHOP").contains("FAILED");
        assertThat(summary.get(1)).contains("DISCORD_CHANNEL_PROVISIONING").contains("BLOCKED");
        assertThat(summary.get(2)).contains("ECONOMY_RIFTECO").contains("ACTIVE");
    }
}
