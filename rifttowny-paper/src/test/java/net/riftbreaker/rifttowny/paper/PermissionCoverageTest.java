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
        PENDING.put(Permission.MANAGE_AREAS,
                "RT-CORE-AREA — the row is ACTIVE for plots, and 3D areas are its outstanding "
                        + "half: no Area type, and rt_area has no production reference");
        PENDING.put(Permission.MANAGE_DISTRICTS,
                "RT-CORE-AREA — districts are the same outstanding half; 'district' appears in no "
                        + "production code but this constant");
        PENDING.put(Permission.MANAGE_SHOPS,
                "RT-MOD-SHOP — PLANNED, and RiftShop exists only as a Capability entry. Not to be "
                        + "confused with SHOP_USE, which is a protection action and is checked");
        PENDING.put(Permission.MANAGE_SPAWNERS,
                "RT-MOD-SPAWNER — PLANNED, and RiftSpawners exists only as a Capability entry. "
                        + "SPAWNER_USE is the protection action and is checked");
        // No module id, deliberately. This said "RT-CORE-LOG" until that was re-verified and found
        // to be an id nothing planned: it was coined in this repository's own notes and then cited
        // twice as a blocker, which is a worse kind of stale than a wrong status — a fictional
        // owner cannot be checked, and reads as though somebody is coming to do the work.
        PENDING.put(Permission.VIEW_LOGS,
                "no owning module: rt_audit is declared in V1 with no writer, and "
                        + "IMPLEMENTATION_PLAN records RiftTowny's RiftLogger adapter as unwritten "
                        + "while the upstream support exists. Untracked rather than scheduled");
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

    @Test
    @DisplayName("every module named as a blocker is a real row in the catalogue")
    void blockersExist() throws IOException {
        // Added after VIEW_LOGS was found waiting on "RT-CORE-LOG", an id that appears nowhere in
        // the catalogue: it was coined in this repository's own notes and then cited twice as
        // though it were planned work. A wrong status can be checked against the code; an owner
        // that does not exist cannot be checked against anything, and reads as though somebody is
        // scheduled to do the work.
        final String catalogue = Files.readString(
                Path.of("..", "FEATURE_CATALOG.md"), StandardCharsets.UTF_8);
        final java.util.regex.Pattern id = java.util.regex.Pattern.compile("RT-[A-Z]+-[A-Z]+");

        final List<String> invented = new ArrayList<>();
        for (final String reason : PENDING.values()) {
            final java.util.regex.Matcher named = id.matcher(reason);
            while (named.find()) {
                if (!catalogue.contains("| " + named.group() + " |")) {
                    invented.add(named.group());
                }
            }
        }

        assertThat(invented)
                .as("named as blocking a permission but not a row in FEATURE_CATALOG. Either add "
                        + "the row or describe the gap without inventing an owner for it")
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
