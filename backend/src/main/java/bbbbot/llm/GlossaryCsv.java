package bbbbot.llm;

import bbbbot.domain.GlossaryEntry;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CSV-Fassung des persoenlichen Glossars fuer Export und Import.
 *
 * <p>Das Format ist bewusst schlicht, damit die Datei von Hand vorbereitet und
 * bearbeitet werden kann: eine Kopfzeile, danach je Zeile
 * {@code Begriff;Bedeutung}. Semikolon statt Komma, weil Excel im deutschen
 * Sprachraum so speichert und oeffnet; die Datei beginnt mit einer BOM, damit
 * Excel sie als UTF-8 erkennt und Umlaute nicht zerlegt.
 *
 * <p>Beim Lesen ist die Klasse bewusst nachsichtig, weil die Datei aus fremden
 * Werkzeugen kommen kann: Trennzeichen (Semikolon, Komma, Tabulator) wird
 * erkannt, Kopfzeile und {@code #}-Kommentare werden uebersprungen, Windows-1252
 * wird als Rueckfall entschluesselt und mehrfach genannte Begriffe werden
 * zusammengefasst.
 */
public final class GlossaryCsv {

    /** Kopfzeile des Exports (wird beim Import wiedererkannt und uebersprungen). */
    static final String HEADER = "Begriff;Bedeutung";

    /** Excel erkennt UTF-8 nur mit BOM - ohne sie wird "Gruen" zu "GrÃ¼n". */
    private static final String BOM = "﻿";

    /** RFC 4180 schreibt CRLF vor; Excel und alle Texteditoren kommen damit klar. */
    private static final String EOL = "\r\n";

    private static final char DEFAULT_DELIMITER = ';';
    private static final char[] CANDIDATE_DELIMITERS = {';', '\t', ','};

    /** Rueckfall-Zeichensatz fuer Dateien, die Excel in der Windows-Codepage gespeichert hat. */
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** Erste Spalte einer Kopfzeile - in diesen Schreibweisen wird sie erkannt. */
    private static final Set<String> HEADER_TERMS =
            Set.of("begriff", "term", "abkuerzung", "abkürzung", "stichwort", "wort");

    private GlossaryCsv() {
    }

    /** Eine gelesene Zeile: Begriff mit optionaler Bedeutung, plus Zeilennummer fuer Meldungen. */
    public record Row(int line, String term, String meaning) {
    }

    /**
     * @param rows    uebernommene Zeilen in Dateireihenfolge, je Begriff nur einmal
     * @param skipped Zeilen, die schon beim Lesen verworfen wurden (kein Begriff, Duplikat)
     * @param notes   Hinweise fuer den Nutzer, mit Zeilennummer
     */
    public record ParseResult(List<Row> rows, int skipped, List<String> notes) {
    }

    /** Glossar als CSV-Datei (UTF-8 mit BOM). Ein leeres Glossar ergibt nur die Kopfzeile. */
    public static byte[] export(List<GlossaryEntry> entries) {
        StringBuilder sb = new StringBuilder(BOM).append(HEADER).append(EOL);
        for (GlossaryEntry entry : entries) {
            String term = entry.getTerm() == null ? "" : entry.getTerm().strip();
            if (term.isEmpty()) continue;
            String meaning = entry.getMeaning() == null ? "" : entry.getMeaning().strip();
            sb.append(field(term)).append(DEFAULT_DELIMITER).append(field(meaning)).append(EOL);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Dateiinhalt als Text. Bevorzugt UTF-8; nur wenn die Bytes darin nicht
     * aufgehen, wird Windows-1252 angenommen - Excel speichert CSV je nach
     * Einstellung in der Windows-Codepage, und ein Umlaut darf daran nicht
     * scheitern.
     */
    public static String decode(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(raw, WINDOWS_1252);
        }
    }

    /**
     * Grobe Pruefung auf Nicht-Textdateien: Wer versehentlich eine .xlsx oder
     * .pdf auswaehlt, soll eine verstaendliche Meldung bekommen statt eines
     * Glossars voller Zeichenmuell.
     */
    public static boolean looksBinary(byte[] raw) {
        int limit = Math.min(raw.length, 8000);
        for (int i = 0; i < limit; i++) {
            if (raw[i] == 0) return true;
        }
        return false;
    }

    /** Liest die CSV-Zeilen ein. Inhaltliche Grenzen (Laengen) prueft der Aufrufer. */
    public static ParseResult parse(String raw) {
        String text = raw.startsWith(BOM) ? raw.substring(1) : raw;
        List<String> notes = new ArrayList<>();
        char delimiter = sniffDelimiter(text);
        List<RawRecord> records = tokenize(text, delimiter, notes);

        // Nur der Begriff und seine Bedeutung werden uebernommen; weitere Spalten
        // (z.B. eigene Notizen in der Tabelle) sollen den Import nicht verhindern.
        boolean extraColumns = false;
        int skipped = 0;
        Map<String, Row> byKey = new LinkedHashMap<>();
        boolean first = true;
        for (RawRecord record : records) {
            String term = record.field(0).strip();
            // Kommentare zuerst: eine Datei darf mit erklaerenden #-Zeilen beginnen,
            // die Kopfzeile steht dann darunter und muss trotzdem erkannt werden.
            if (term.startsWith("#")) continue;
            if (first) {
                first = false;
                if (HEADER_TERMS.contains(term.toLowerCase(Locale.GERMAN))) continue;
            }
            if (record.fields().size() > 2) extraColumns = true;
            if (term.isEmpty()) {
                skipped++;
                notes.add("Zeile " + record.line() + ": kein Begriff angegeben");
                continue;
            }
            String meaning = record.field(1).strip();
            Row row = new Row(record.line(), term, meaning.isEmpty() ? null : meaning);
            Row previous = byKey.put(GlossaryEntry.normalizeKey(term), row);
            if (previous != null) {
                skipped++;
                notes.add("Zeile " + record.line() + ": \"" + term + "\" steht schon in Zeile "
                        + previous.line() + " - die spaetere Zeile gilt");
            }
        }
        if (extraColumns) {
            notes.add("Zusaetzliche Spalten wurden ignoriert - uebernommen werden nur Begriff und Bedeutung");
        }
        return new ParseResult(List.copyOf(byKey.values()), skipped, notes);
    }

    /** Ein Datensatz der Datei mit der Zeile, in der er beginnt. */
    private record RawRecord(int line, List<String> fields) {
        String field(int index) {
            return index < fields.size() ? fields.get(index) : "";
        }

        boolean isBlank() {
            return fields.stream().allMatch(String::isBlank);
        }
    }

    /**
     * Trennzeichen aus der ersten inhaltlichen Zeile ableiten. Semikolon
     * gewinnt bei Gleichstand, weil der Export es verwendet.
     */
    private static char sniffDelimiter(String text) {
        String line = firstContentLine(text);
        char best = DEFAULT_DELIMITER;
        int bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int count = countOutsideQuotes(line, candidate);
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    private static String firstContentLine(String text) {
        for (String line : text.split("\r\n|\r|\n")) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) return line;
        }
        return "";
    }

    private static int countOutsideQuotes(String line, char needle) {
        int count = 0;
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == needle && !inQuotes) {
                count++;
            }
        }
        return count;
    }

    /**
     * Zerlegt die Datei in Datensaetze und Felder (RFC 4180): Ein Feld in
     * Anfuehrungszeichen darf Trennzeichen und Zeilenumbrueche enthalten,
     * {@code ""} steht darin fuer ein einzelnes Anfuehrungszeichen.
     */
    private static List<RawRecord> tokenize(String text, char delimiter, List<String> notes) {
        List<RawRecord> records = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int line = 1;
        int recordLine = 1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else if (c == '\r' || c == '\n') {
                    // Zeilenumbruch innerhalb der Bedeutung - vereinheitlicht auf \n
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    field.append('\n');
                    line++;
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty()) {
                inQuotes = true;
            } else if (c == delimiter) {
                fields.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                fields.add(field.toString());
                field.setLength(0);
                addRecord(records, recordLine, fields);
                fields = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                field.append(c);
            }
        }
        if (inQuotes) {
            notes.add("Zeile " + recordLine + ": Anfuehrungszeichen nicht geschlossen");
        }
        if (!field.isEmpty() || !fields.isEmpty()) {
            fields.add(field.toString());
            addRecord(records, recordLine, fields);
        }
        return records;
    }

    /** Leerzeilen (auch reine Trennzeichen-Zeilen) gehoeren nicht zum Inhalt. */
    private static void addRecord(List<RawRecord> records, int line, List<String> fields) {
        RawRecord record = new RawRecord(line, List.copyOf(fields));
        if (!record.isBlank()) records.add(record);
    }

    /** Feld ausgeben; Anfuehrungszeichen nur, wo sie noetig sind - das haelt die Datei lesbar. */
    private static String field(String value) {
        boolean needsQuotes = value.indexOf(DEFAULT_DELIMITER) >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || !value.equals(value.strip());
        if (!needsQuotes) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
