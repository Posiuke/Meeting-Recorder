package bbbbot.bot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stille-WAV fuer Chromiums Fake-Mikrofon.
 *
 * <p>{@code --use-fake-device-for-media-stream} allein liefert KEINE Stille,
 * sondern einen Piepton pro Sekunde. Landet der Bot mit Mikrofon statt "Nur
 * zuhoeren" im Raum (Mikrofon-Fallback oder Auto-Join des Servers), hoeren alle
 * Teilnehmer das Piepen und es landet in der Aufnahme (Issue #30). Mit
 * {@code --use-file-for-fake-audio-capture} spielt das Fake-Mikrofon stattdessen
 * diese Datei in Schleife ab.
 */
final class SilentMicrophone {

    private static final int SAMPLE_RATE = 48_000;
    private static final short CHANNELS = 1;
    private static final short BITS_PER_SAMPLE = 16;

    private static volatile Path file;

    private SilentMicrophone() {
    }

    /** Pfad zur (einmalig erzeugten) Stille-WAV, eine Sekunde 16-bit-PCM mono. */
    static Path wavFile() {
        Path f = file;
        if (f != null && Files.exists(f)) return f;
        synchronized (SilentMicrophone.class) {
            if (file == null || !Files.exists(file)) {
                file = create();
            }
            return file;
        }
    }

    private static Path create() {
        int dataSize = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + dataSize).put("WAVE".getBytes());
        wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort(CHANNELS)
                .putInt(SAMPLE_RATE).putInt(byteRate)
                .putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8)).putShort(BITS_PER_SAMPLE);
        wav.put("data".getBytes()).putInt(dataSize);
        // Rest bleibt 0 = Stille.
        try {
            Path path = Files.createTempFile("bbbbot-silence-", ".wav");
            Files.write(path, wav.array());
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("Stille-WAV fuer das Fake-Mikrofon nicht anlegbar", e);
        }
    }
}
