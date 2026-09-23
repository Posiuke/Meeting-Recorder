package bbbbot.domain;

/**
 * Gewaehlte Auswertungs-Vorlage einer kuenftigen Aufnahme: Prompt samt Name,
 * Modell und Temperatur. Jeder Wert {@code null} heisst "Vorgabe des Admins".
 *
 * <p>Ein Bot legt seine Aufnahmen erst an, wenn im Raum aufgenommen wird - bis
 * dahin haelt die Bot-Session die Wahl fest und gibt sie jeder Aufnahme mit,
 * damit schon die erste (auch sofortige) Auswertung mit ihr laeuft.
 *
 * @param prompt       Auswertungs-Prompt; null = Admin-Standard
 * @param templateName Name der Vorlage; benennt die erzeugte Fassung
 * @param model        Modell; null = Admin-Vorgabe {@code llm.model}
 * @param temperature  Temperatur; null = Admin-Vorgabe {@code llm.temperature}
 */
public record SummaryChoice(String prompt, String templateName, String model, Double temperature) {

    /** Keine Vorlage gewaehlt - es gilt durchgehend die Vorgabe des Admins. */
    public static final SummaryChoice DEFAULT = new SummaryChoice(null, null, null, null);
}
