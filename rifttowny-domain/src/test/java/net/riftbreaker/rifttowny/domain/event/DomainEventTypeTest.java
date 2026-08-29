package net.riftbreaker.rifttowny.domain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names events go out under.
 *
 * <p>{@code type()} is not an internal detail. It is written to {@code rt_outbox.event_type} and is
 * what every consumer downstream matches on — the Discord relay, the network transport, anything
 * subscribing later. Two events sharing a name, or one quietly renamed, breaks those consumers
 * without breaking anything here: the row still writes, the run still succeeds, and the wrong
 * announcement goes out or none does.
 *
 * <p>The compiler already guarantees each event has a type, since {@code type()} is abstract on a
 * sealed interface. What it cannot see is whether two of them say the same thing, or whether one
 * says it in a different shape from the rest.
 */
class DomainEventTypeTest {

    private static final Path SOURCE = Path.of(
            "src/main/java/net/riftbreaker/rifttowny/domain/event/DomainEvent.java");

    /** The string an event reports itself as. */
    private static final Pattern TYPE = Pattern.compile("return \"([^\"]*)\";");

    @Test
    @DisplayName("no two events go out under the same name")
    void typesAreUnique() {
        final List<String> types = declaredTypes();

        assertThat(types)
                .as("a consumer matching on event_type cannot tell two events apart if they "
                        + "share a name, and nothing here would fail")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every event in the sealed hierarchy declares one")
    void everyEventHasAType() {
        // Cross-checks reflection against the source. The sealed interface knows exactly how many
        // events exist; the file knows how many name themselves. A mismatch means one was added
        // with a type this test cannot see, and it would then go unchecked for uniqueness too.
        final Class<?>[] events = DomainEvent.class.getPermittedSubclasses();

        assertThat(events).as("a sealed interface with no cases would make this vacuous").isNotEmpty();

        // Everything except the events that build their name instead of writing it, which a scan
        // of the source cannot see. Named rather than subtracted blindly: a second computed event
        // must be added here on purpose, and covered by the test below, rather than making this
        // count quietly correct again.
        final List<String> computed = List.of("RoleChanged");
        for (final String name : computed) {
            assertThat(java.util.Arrays.stream(events).map(Class::getSimpleName))
                    .as("%s is listed as computed but is not an event", name)
                    .contains(name);
        }

        assertThat(declaredTypes())
                .as("%d events are permitted, %d of them computed, and this many name themselves "
                        + "in the source", events.length, computed.size())
                .hasSize(events.length - computed.size());
    }

    @Test
    @DisplayName("the one computed name follows the same shape as the written ones")
    void computedNamesMatchTheRest() {
        // RoleChanged is the only event whose type is built rather than written, one name per
        // RoleAction, and being built is how it drifted: it read PERMISSION_GRANTED out of the
        // enum as "role.permission_granted" while every written name uses a hyphen. Nothing
        // compared the two halves, because nothing looked at the computed half at all.
        final Pattern shape = Pattern.compile("[a-z]+(\\.[a-z]+(-[a-z]+)*)+");

        for (final DomainEvent.RoleAction action : DomainEvent.RoleAction.values()) {
            final String type = new DomainEvent.RoleChanged(
                    net.riftbreaker.rifttowny.domain.org.OrganisationScope.TOWN,
                    java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "Officer",
                    action, null).type();

            assertThat(type).as("%s", action).matches(shape);
            assertThat(declaredTypes())
                    .as("%s collides with a written name", type)
                    .doesNotContain(type);
        }
    }

    @Test
    @DisplayName("names share one shape, so a consumer can match on a prefix")
    void namesFollowTheConvention() {
        // Lower case, dot-separated, hyphens inside a word. The prefix is what a subscriber
        // filters on - everything a town does starts "town." - so a name in another shape is not
        // merely untidy, it is invisible to a filter that was written for the rest.
        final Pattern shape = Pattern.compile("[a-z]+(\\.[a-z]+(-[a-z]+)*)+");

        assertThat(declaredTypes()).allSatisfy(type ->
                assertThat(type).as("%s", type).matches(shape));
    }

    @Test
    @DisplayName("every name is prefixed by something that owns it")
    void namesAreOwned() {
        // Not an exhaustive list of allowed prefixes, which would need editing for every feature.
        // Only that the first segment is one of the things this plugin actually has, so a typo
        // like "twon.founded" is caught rather than shipped.
        final List<String> owners =
                List.of("town", "nation", "plot", "ruin", "flag", "tax", "role", "resident");

        assertThat(declaredTypes()).allSatisfy(type ->
                assertThat(owners)
                        .as("%s is owned by nothing this plugin has", type)
                        .contains(type.substring(0, type.indexOf('.'))));
    }

    private static List<String> declaredTypes() {
        final List<String> types = new ArrayList<>();
        try {
            final Matcher found = TYPE.matcher(Files.readString(SOURCE, StandardCharsets.UTF_8));
            while (found.find()) {
                types.add(found.group(1));
            }
        } catch (final IOException unreadable) {
            throw new IllegalStateException("Could not read " + SOURCE, unreadable);
        }
        return types;
    }
}
