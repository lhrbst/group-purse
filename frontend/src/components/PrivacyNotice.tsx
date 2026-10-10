import { Anchor, Modal, Text, List } from '@mantine/core';
import { useState } from 'react';

/** Datenschutzhinweis zur Registrierung. Der Wortlaut sollte vom Verein bzw. Träger geprüft und ggf. angepasst werden. */
export function PrivacyNotice() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <Anchor component="button" type="button" size="sm" onClick={() => setOpen(true)}>
        Datenschutzhinweis lesen
      </Anchor>
      <Modal opened={open} onClose={() => setOpen(false)} title="Datenschutzhinweis" size="lg">
        <Text size="sm" mb="xs">
          Die Gruppenkasse dokumentiert die Einnahmen und Ausgaben des Elternvereins. Dafür speichern wir:
        </Text>
        <List size="sm" spacing={4} mb="xs">
          <List.Item>deinen Namen und deine E-Mail-Adresse (zur Anmeldung und damit Buchungen dir zugeordnet werden können),</List.Item>
          <List.Item>jede von dir erfasste Ausgabe mit Betrag, Datum, Zweck und Belegfoto,</List.Item>
          <List.Item>technisch notwendige Daten wie Zeitpunkte deiner Aktionen (Protokoll zur Nachvollziehbarkeit).</List.Item>
        </List>
        <Text size="sm" mb="xs">
          Belegfotos können sensible Inhalte zeigen (z. B. Apothekenbons). Sie sind nur für die Kassenverwalter und für dich sichtbar.
          Bitte fotografiere nur den Beleg. Gesundheitsdaten von Bewohnern werden nicht gespeichert; Bewohner erscheinen nur mit Kürzel.
        </Text>
        <Text size="sm" mb="xs">
          Zur automatischen Erkennung von Händler, Datum und Betrag kann das Belegfoto an einen KI-Dienst (OpenAI, Verarbeitung in der EU)
          übermittelt werden. Das Foto wird dort nicht zu eigenen Zwecken gespeichert. Buchungen und Belege werden aus Buchführungsgründen
          in der Regel zehn Jahre aufbewahrt. Ausgeschiedene Personen werden gesperrt, ihre Buchungen bleiben mit Namen erhalten.
        </Text>
        <Text size="sm">
          Die Daten liegen auf einem Server in Deutschland. Du kannst Auskunft, Berichtigung und Einschränkung der Verarbeitung beim
          Vorstand des Vereins verlangen. Mit deiner Zustimmung bestätigst du, diesen Hinweis gelesen zu haben.
        </Text>
      </Modal>
    </>
  );
}
