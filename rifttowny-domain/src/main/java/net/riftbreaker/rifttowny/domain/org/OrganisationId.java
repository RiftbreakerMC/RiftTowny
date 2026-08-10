package net.riftbreaker.rifttowny.domain.org;

import java.util.UUID;

/**
 * A town or a nation, identified by a UUID that is assigned once and never changes.
 *
 * <p>Typed rather than a bare {@link UUID} because the compiler is the only thing that reliably
 * stops a town id being passed where a nation id was meant. Both are 36 characters of hex; a test
 * would not catch it, and the failure would be a role or a bank attached to the wrong
 * organisation.</p>
 *
 * <p>Renaming an organisation or transferring its leadership must never change this value — the
 * civic bank account is keyed on it.</p>
 */
public sealed interface OrganisationId permits TownId, NationId {

    /** The stable UUID. */
    UUID value();

    /** Which kind of organisation this identifies. */
    OrganisationScope scope();
}
