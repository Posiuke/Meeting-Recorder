package bbbbot.api;

import bbbbot.domain.GlossaryEntry;
import bbbbot.llm.GlossaryService;
import bbbbot.settings.SettingsService;
import bbbbot.stt.transcribe.AwsTranscriber;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Amazon Transcribe im Admin-Bereich: Das gemeinsame Glossar wird als Custom
 * Vocabulary uebertragen - so kennt die Spracherkennung Eigennamen,
 * Fachbegriffe und Abkuerzungen, die sie sonst falsch schreiben wuerde.
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminTranscribeController {

    private final AwsTranscriber transcriber;
    private final GlossaryService glossary;
    private final SettingsService settings;

    public AdminTranscribeController(AwsTranscriber transcriber, GlossaryService glossary,
                                     SettingsService settings) {
        this.transcriber = transcriber;
        this.glossary = glossary;
        this.settings = settings;
    }

    @PostMapping("/transcribe-vocabulary")
    public AwsTranscriber.VocabularyResult syncVocabulary() {
        List<String> terms = glossary.entriesOf(null).stream().map(GlossaryEntry::getTerm).toList();
        String language = settings.get(SettingsService.WHISPER_LANGUAGE);
        AwsTranscriber.VocabularyResult result = transcriber.syncVocabulary(terms,
                "auto".equalsIgnoreCase(language) ? "de" : language);
        if (result.success()) {
            settings.update(Map.of(SettingsService.WHISPER_AWS_VOCABULARY, result.name()));
        }
        return result;
    }
}
