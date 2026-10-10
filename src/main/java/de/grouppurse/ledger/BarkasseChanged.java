package de.grouppurse.ledger;

import java.util.UUID;

/** Ereignis nach jeder Bewegung der Barkasse; löst u.a. die Prüfung der Kassenwarnung aus. */
public record BarkasseChanged(UUID wohngruppeId) {
}
