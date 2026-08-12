package bbbbot.api;

import bbbbot.domain.AppUser;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.ShareLink;
import bbbbot.domain.Summary;
import bbbbot.recording.ParticipantService;
import bbbbot.recording.RecordingMediaService;
import bbbbot.repository.Repositories.AppUserRepo;
import bbbbot.repository.Repositories.RecordingRepo;
import bbbbot.repository.Repositories.RecordingSegmentRepo;
import bbbbot.repository.Repositories.SummaryRepo;
import bbbbot.sharing.ShareLinkService;
import bbbbot.stt.TranscriptAssembler;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Oeffentliche Freigabe-Ansicht einer Aufnahme: erreichbar ohne Anmeldung, allein
 * ueber das Token des Freigabe-Links (siehe {@link ShareLink}).
 *
 * <p>Sichtbar sind Video, Audio-Segmente, Transkript und Zusammenfassung - also
 * die Inhalte der Aufnahme selbst. Chat-Protokoll, Sitzungsprotokoll,
 * Verarbeitungs-Historie und alle schreibenden Aktionen bleiben der angemeldeten
 * Ansicht vorbehalten.
 *
 * <p>Unbekannte, widerrufene und abgelaufene Tokens werden gleich behandelt (404):
 * Wer eine Adresse ausprobiert, soll nicht erfahren, ob es sie einmal gab.
 */
@RestController
@RequestMapping("/api/public/shares/{token}")
public class PublicShareController {

    private final ShareLinkService shareLinks;
    private final RecordingRepo recordingRepo;
    private final RecordingSegmentRepo segmentRepo;
    private final SummaryRepo summaryRepo;
    private final AppUserRepo userRepo;
    private final ParticipantService participantService;
    private final RecordingMediaService media;

    public PublicShareController(ShareLinkService shareLinks, RecordingRepo recordingRepo,
                                 RecordingSegmentRepo segmentRepo, SummaryRepo summaryRepo,
                                 AppUserRepo userRepo, ParticipantService participantService,
                                 RecordingMediaService media) {
        this.shareLinks = shareLinks;
        this.recordingRepo = recordingRepo;
        this.segmentRepo = segmentRepo;
        this.summaryRepo = summaryRepo;
        this.userRepo = userRepo;
        this.participantService = participantService;
        this.media = media;
    }

    @GetMapping
    public Dtos.PublicShareView view(@PathVariable String token) {
        ShareLink link = requireLink(token);
        Recording recording = requireRecording(link);
        shareLinks.recordView(link);

        List<RecordingSegment> segments = segmentRepo.findByRecordingIdOrderBySeq(recording.getId());
        // Geglaettete Fassung, falls vorhanden - sie ist auch die Grundlage der
        // Zusammenfassung. Eine Umschaltung aufs Whisper-Original braucht die
        // oeffentliche Ansicht nicht.
        List<TranscriptAssembler.Entry> entries = TranscriptAssembler.assemble(segments, true);
        Summary summary = summaryRepo.findByRecordingIdOrderByCreatedAtDesc(recording.getId()).stream()
                .filter(s -> s.getStatus() == Summary.Status.DONE && s.getMarkdown() != null)
                .findFirst()
                .orElse(null);
        String sharedBy = userRepo.findById(recording.getOwnerId())
                .map(AppUser::getDisplayName)
                .orElse(null);

        return new Dtos.PublicShareView(
                recording.getTitle(), recording.getStartedAt(), recording.getEndedAt(),
                recording.getDurationMs(),
                (recording.getSource() == null ? Recording.Source.BOT : recording.getSource()).name(),
                sharedBy, media.hasVideo(recording),
                segments.stream()
                        .filter(s -> s.getMp3Path() != null)
                        .map(Dtos.PublicSegmentView::of)
                        .toList(),
                summary == null ? null : summary.getMarkdown(),
                summary == null ? null : summary.getCreatedAt(),
                TranscriptAssembler.toText(entries),
                entries.stream()
                        .map(e -> new Dtos.TranscriptEntry(e.startSeconds(), e.speaker(), e.text()))
                        .toList(),
                participantService.list(recording.getId()).stream()
                        .map(Dtos.ParticipantView::of)
                        .toList(),
                link.getExpiresAt());
    }

    @GetMapping("/segments/{segmentId}/audio")
    public ResponseEntity<FileSystemResource> audio(@PathVariable String token,
                                                    @PathVariable UUID segmentId) {
        Recording recording = requireRecording(requireLink(token));
        return media.audio(recording.getId(), segmentId);
    }

    @GetMapping("/video")
    public ResponseEntity<FileSystemResource> video(@PathVariable String token) {
        return media.video(requireRecording(requireLink(token)));
    }

    @GetMapping("/video/download")
    public ResponseEntity<FileSystemResource> videoDownload(@PathVariable String token) {
        return media.videoDownload(requireRecording(requireLink(token)));
    }

    @GetMapping("/summary/download")
    public ResponseEntity<byte[]> summaryDownload(@PathVariable String token) {
        return media.summaryDownload(requireRecording(requireLink(token)));
    }

    private ShareLink requireLink(String token) {
        return shareLinks.resolve(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dieser Freigabe-Link ist ungueltig oder abgelaufen"));
    }

    /** Zur Aufnahme des Links; sie kann inzwischen geloescht worden sein. */
    private Recording requireRecording(ShareLink link) {
        return recordingRepo.findById(link.getRecordingId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Die freigegebene Aufnahme existiert nicht mehr"));
    }
}
