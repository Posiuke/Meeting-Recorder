package bbbbot.bot;

import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Startet/stoppt die Browser-seitige Audio-Aufnahme (Portierung von
 * src/recorder.ts): MediaRecorder in der Seite, Chunks kommen ueber das
 * Playwright-Binding "node_receiveAudioChunk" als Byte-Listen an.
 */
public class PageAudioRecorder {

    private static final Logger log = LoggerFactory.getLogger(PageAudioRecorder.class);

    public interface ChunkSink {
        void onChunk(byte[] data, boolean isLast);
    }

    private final Page page;
    private boolean bindingInstalled;

    public PageAudioRecorder(Page page) {
        this.page = page;
    }

    /** Binding einmalig installieren; der Sink kann pro Aufnahme gewechselt werden. */
    private volatile ChunkSink sink;

    public void installBinding() {
        if (bindingInstalled) return;
        page.exposeBinding("node_receiveAudioChunk", (source, args) -> {
            try {
                if (args.length == 0 || !(args[0] instanceof Map<?, ?> payload)) return null;
                Object bytesObj = payload.get("bytes");
                boolean last = Boolean.TRUE.equals(payload.get("last"));
                ChunkSink currentSink = sink;
                if (currentSink != null && bytesObj instanceof List<?> byteList) {
                    byte[] data = new byte[byteList.size()];
                    for (int i = 0; i < byteList.size(); i++) {
                        data[i] = (byte) ((Number) byteList.get(i)).intValue();
                    }
                    currentSink.onChunk(data, last);
                }
            } catch (RuntimeException e) {
                log.error("Fehler im Audio-Chunk-Binding: {}", e.getMessage());
            }
            return null;
        });
        bindingInstalled = true;
        log.info("Audio-Chunk-Binding installiert.");
    }

    /** Nach einem Browser-Neustart (Reconnect) muss das Binding neu installiert werden. */
    public void resetBindingState() {
        bindingInstalled = false;
    }

    public void start(long segmentMs, ChunkSink chunkSink) {
        installBinding();
        this.sink = chunkSink;
        // Playwright-Java serialisiert nur Integer/Double/String/Boolean - KEIN Long.
        // segmentMs (ms) passt problemlos in int, daher expliziter Cast, sonst
        // scheitert page.evaluate mit "Unsupported type of argument".
        int segmentMsArg = (int) Math.min(segmentMs, Integer.MAX_VALUE);
        page.evaluate(BrowserScripts.load(BrowserScripts.RECORDER), segmentMsArg);
        log.info("Browser-Aufnahme gestartet (Segment-Rotation alle {} ms).", segmentMsArg);
    }

    /** Stoppt die Aufnahme; der letzte Chunk kommt mit last=true ueber das Binding. */
    public void stop() {
        try {
            page.evaluate("""
                async () => {
                  if (window.__BBB_RECORDER_STOP__) {
                    await window.__BBB_RECORDER_STOP__();
                    delete window.__BBB_RECORDER_STOP__;
                  }
                }""");
        } catch (RuntimeException e) {
            log.warn("Fehler beim Stoppen der Browser-Aufnahme: {}", e.getMessage());
        }
        log.info("Browser-Aufnahme gestoppt.");
    }

    public void clearSink() {
        this.sink = null;
    }
}
