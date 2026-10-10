export const ART_LABEL: Record<string, string> = {
  ANFANGSBESTAND: 'Anfangsbestand',
  AUFFUELLUNG: 'Auffüllung',
  AUSGABE: 'Ausgabe',
  RUECKGABE: 'Rückgabe ans Konto',
  KORREKTUR: 'Korrektur',
  KONTOAUSGABE: 'Kontoausgabe',
  STORNO: 'Storno',
};

export const STATUS_LABEL: Record<string, string> = {
  GEBUCHT: 'gebucht',
  EINGEREICHT: 'eingereicht',
  GEPRUEFT: 'geprüft',
  ABGELEHNT: 'abgelehnt',
  STORNIERT: 'storniert',
  VERARBEITUNG: 'wird verarbeitet',
  MANUELL: 'bitte erfassen',
};

export const STATUS_COLOR: Record<string, string> = {
  GEBUCHT: 'gray',
  EINGEREICHT: 'blue',
  GEPRUEFT: 'green',
  ABGELEHNT: 'red',
  STORNIERT: 'gray',
  VERARBEITUNG: 'violet',
  MANUELL: 'orange',
};

export const KONTO_LABEL: Record<string, string> = {
  BARKASSE: 'Barkasse',
  VEREINSKONTO: 'Vereinskonto',
};

export const ROLLE_LABEL: Record<string, string> = {
  KASSENVERWALTER: 'Kassenverwalter',
  PFLEGEKRAFT: 'Teammitglied',
};

export const MEMBER_STATUS_LABEL: Record<string, string> = {
  WARTEND: 'wartend',
  AKTIV: 'aktiv',
  GESPERRT: 'gesperrt',
};
