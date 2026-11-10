package bbbbot.stt.transcribe;

import java.nio.file.Path;
import java.util.List;

/**
 * Cloud-Spracherkennung, die eine ganze Aufnahme in EINEM Auftrag verarbeitet
 * (Amazon Transcribe, ElevenLabs). So bleiben die Sprecher ueber alle Segmente
 * dieselben; {@link SegmentSplitter} verteilt das Ergebnis danach wieder auf die
 * Segmente.
 */
public interface WholeFileTranscriber {

    /** Ergebnis eines Auftrags; bei Erfolg die Aeusserungen und die (erkannte) Sprache. */
    record Result(boolean success, List<TranscribeResult.Utterance> utterances, String languageCode,
                  String error) {
        public static Result failed(String error) {
            return new Result(false, List.of(), null, error);
        }
    }

    /**
     * @param audio    MP3-Datei (lokal)
     * @param language ISO-Sprache ("de", "en", ...); leer = automatisch erkennen
     * @param diarize  Sprecher unterscheiden
     */
    Result transcribe(Path audio, String language, boolean diarize);

    /** Laengste Aufnahme, die der Anbieter am Stueck annimmt. */
    long maxDurationMs();

    /** Name fuer Protokoll und Fehlermeldungen. */
    String displayName();
}
