package net.riftbreaker.rifttowny.paper;

import net.riftbreaker.rifttowny.domain.role.Permission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A permission nothing checks is a switch with no wire behind it.
 *
 * <p>Every constant here is grantable: it appears in {@code /town role grant}'s completion, is
 * stored on the role, and shows in {@code /town role list}. So a mayor can hand somebody
 * {@code MANAGE_TAXES}, see it listed, and have granted precisely nothing. {@code CHAT_TOWN} and
 * {@code CHAT_NATION} were in that state for months — in {@code MEMBER}'s default set, checked by
 * no code at all — and were found by a sweep rather than by anybody playing.
 *
 * <p>Seven are still unchecked, and all seven are waiting on a module that has not shipped. That is
 * a legitimate state; being unable to tell it apart from an oversight is not. So they are named
 * below with what each waits for, and anything else unchecked fails the build.
 *
 * <p>The list is meant to shrink. Removing a name from it when its module lands is the point.
 */
class PermissionCoverageTest {

    /**
     * Permissions no production code checks yet, and what each is waiting for.
     *
     * <p>Verified against the feature catalogue rather than guessed: each named module is a real row
     * there, and none of these is merely forgotten.</p>
     */
    private static final Map<Permission, String> PENDING = new LinkedHashMap<>();

    static {
        PENDING.put(Permission.CHAT_ALLY,
                "RT-MOD-CHAT's /ac — no ALLY constant in RiftChat, and no allies until "
                        + "RT-MOD-DIPLOMACY");
        PENDING.put(Permission.MANAGE_AREAS, "RT-CORE-AREA — 3D areas are unbuilt");
        PENDING.put(Permission.MANAGE_DISTRICTS, "RT-CORE-AREA — districts are unbuilt");
        PENDING.put(Permission.MANAGE_SHOPS, "RT-MOD-SHOP");
        PENDING.put(Permission.MANAGE_SPAWNERS, "RT-MOD-SPAWNER");
        PENDING.put(Permission.VIEW_LOGS, "RT-CORE-LOG — rt_audit has no writer");
    }

    /** Where a permission is declared or defaulted rather than checked. */
    private static final List<String> DECLARATIONS =
            List.of("role/Permission.java", "role/SystemRole.java");

    private static final Path MODULES = Path.of("..");

    @Test
    @DisplayName("every permission is either checked in production code or a known pending one")
    void everyPermissionIsCheckedOrPending() throws IOException {
        final String sources = productionSources();
        assertThat(sources)
                .as("scanning no sources would make this test pass by reading nothing")
                .hasSizeGreaterThan(100_000);

        final List<String> unchecked = new ArrayList<>();
        for (final Permission permission : Permission.values()) {
            if (!sources.contains("Permission." + permission.name())
                    && !PENDING.containsKey(permission)) {
                unchecked.add(permission.name());
            }
        }

        assertThat(unchecked)
                .as("these can be granted through /town role grant and are read by nothing, so "
                        + "granting one does nothing at all. Either check it somewhere, or add it "
                        + "to PENDING with the module it waits for")
                .isEmpty();
    }

    @Test
    @DisplayName("nothing lingers on the pending list after its module arrives")
    void pendingPermissionsAreStillPending() throws IOException {
        final String sources = productionSources();

        final List<String> nowChecked = new ArrayList<>();
        for (final Map.Entry<Permission, String> pending : PENDING.entrySet()) {
            if (sources.contains("Permission." + pending.getKey().name())) {
                nowChecked.add(pending.getKey().name());
            }
        }

        assertThat(nowChecked)
                .as("these are checked now, so the excuse recorded beside them is stale. Take them "
                        + "off the list — it is meant to shrink")
                .isEmpty();
    }

    /**
     * Every main source in the build, minus the files that only declare permissions.
     *
     * <p>{@code Permission} itself and {@code SystemRole} name almost every constant — one to
     * declare it, the other to put it in a default set — and counting those as checks would make
     * this test assert nothing.</p>
     */
    private static String productionSources() throws IOException {
        final StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MODULES)) {
            for (final Path path : files.filter(PermissionCoverageTest::isProductionSource).toList()) {
                all.append(Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    private static boolean isProductionSource(final Path path) {
        final String name = path.toString().replace('\\', '/');
        return name.endsWith(".java")
                && name.contains("/src/main/java/")
                && DECLARATIONS.stream().noneMatch(name::endsWith);
    }
}
