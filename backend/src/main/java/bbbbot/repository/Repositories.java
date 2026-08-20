package bbbbot.repository;

import bbbbot.domain.ApiKey;
import bbbbot.domain.AppSetting;
import bbbbot.domain.AppUser;
import bbbbot.domain.BotSession;
import bbbbot.domain.GlossaryEntry;
import bbbbot.domain.GroupMember;
import bbbbot.domain.Participant;
import bbbbot.domain.ProcessingJob;
import bbbbot.domain.PromptTemplate;
import bbbbot.domain.Recording;
import bbbbot.domain.RecordingSegment;
import bbbbot.domain.RecordingTag;
import bbbbot.domain.RecordingDocument;
import bbbbot.domain.ShareGrant;
import bbbbot.domain.ShareLink;
import bbbbot.domain.Summary;
import bbbbot.domain.UserGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface Repositories {

    interface AppUserRepo extends JpaRepository<AppUser, UUID> {
        Optional<AppUser> findByUsernameIgnoreCase(String username);
        List<AppUser> findTop20ByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(String u, String d);
    }

    interface ApiKeyRepo extends JpaRepository<ApiKey, UUID> {
        Optional<ApiKey> findByTokenHash(String tokenHash);
        List<ApiKey> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
        long countByOwnerId(UUID ownerId);
    }

    interface UserGroupRepo extends JpaRepository<UserGroup, UUID> {
        @Query("select g from UserGroup g where g.ownerId = :userId or g.id in (select m.groupId from GroupMember m where m.userId = :userId)")
        List<UserGroup> findAllVisibleTo(@Param("userId") UUID userId);
        boolean existsByNameIgnoreCase(String name);
    }

    interface GroupMemberRepo extends JpaRepository<GroupMember, UUID> {
        List<GroupMember> findByGroupId(UUID groupId);
        Optional<GroupMember> findByGroupIdAndUserId(UUID groupId, UUID userId);
        List<GroupMember> findByUserId(UUID userId);
        void deleteByGroupIdAndUserId(UUID groupId, UUID userId);
    }

    interface BotSessionRepo extends JpaRepository<BotSession, UUID> {
        List<BotSession> findByStatusIn(List<BotSession.Status> statuses);
        List<BotSession> findTop50ByOrderByCreatedAtDesc();
        List<BotSession> findTop50ByCreatedByOrderByCreatedAtDesc(UUID createdBy);
    }

    interface RecordingRepo extends JpaRepository<Recording, UUID> {
        List<Recording> findByOwnerIdOrderByStartedAtDesc(UUID ownerId);
        List<Recording> findByStatusIn(List<Recording.Status> statuses);
        List<Recording> findByStartedAtBefore(java.time.Instant cutoff);
        List<Recording> findBySourceAndStatus(Recording.Source source, Recording.Status status);

        @Query("""
            select distinct r from Recording r where r.ownerId = :userId
              or r.id in (select s.recordingId from ShareGrant s where s.granteeUserId = :userId)
              or r.id in (select s.recordingId from ShareGrant s where s.granteeGroupId in
                  (select m.groupId from GroupMember m where m.userId = :userId))
              or r.id in (select s.recordingId from ShareGrant s where s.granteeGroupId in
                  (select g.id from UserGroup g where g.ownerId = :userId))
            order by r.startedAt desc
            """)
        List<Recording> findAllAccessibleBy(@Param("userId") UUID userId);

        List<Recording> findByVideoStatusIn(List<Recording.VideoStatus> statuses);

        /**
         * Schreibt ausschliesslich die beiden Video-Spalten.
         *
         * <p>Bewusst ein gezieltes UPDATE statt {@code save(entity)}: Das Muxen
         * laeuft parallel zur Verarbeitung derselben Aufnahme. Ein save() der
         * ganzen Entity wuerde alle Spalten aus einem alten Schnappschuss
         * zurueckschreiben - und damit z.B. den inzwischen erreichten Status
         * ueberschreiben. Umgekehrt darf die Verarbeitung die Video-Spalten
         * nicht anfassen (siehe ProcessingService).
         */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Transactional
        @Query("update Recording r set r.videoStatus = :status, r.videoPath = :path where r.id = :id")
        int updateVideoState(@Param("id") UUID id,
                             @Param("status") Recording.VideoStatus status,
                             @Param("path") String path);
    }

    interface RecordingSegmentRepo extends JpaRepository<RecordingSegment, UUID> {
        List<RecordingSegment> findByRecordingIdOrderBySeq(UUID recordingId);

        /**
         * Aufnahmen, deren Transkript den Suchbegriff enthaelt (Kleinschreibung, %...%).
         * Das Fluchtzeichen '!' muss zu RecordingSearch passen.
         */
        @Query("""
            select distinct s.recordingId from RecordingSegment s
              where s.transcriptText is not null and lower(s.transcriptText) like :pattern escape '!'
            """)
        List<UUID> findRecordingIdsByTranscriptLike(@Param("pattern") String pattern);
    }

    interface RecordingTagRepo extends JpaRepository<RecordingTag, UUID> {
        List<RecordingTag> findByRecordingIdOrderByNameKeyAsc(UUID recordingId);
        List<RecordingTag> findByRecordingIdIn(List<UUID> recordingIds);
        Optional<RecordingTag> findByRecordingIdAndNameKey(UUID recordingId, String nameKey);
        long countByRecordingId(UUID recordingId);

        /** Aufnahmen mit genau diesem Schlagwort. */
        @Query("select t.recordingId from RecordingTag t where t.nameKey = :nameKey")
        List<UUID> findRecordingIdsByNameKey(@Param("nameKey") String nameKey);

        /** Aufnahmen, deren Schlagwort den Suchbegriff enthaelt (%...%, Fluchtzeichen '!'). */
        @Query("select distinct t.recordingId from RecordingTag t where t.nameKey like :pattern escape '!'")
        List<UUID> findRecordingIdsByNameKeyLike(@Param("pattern") String pattern);
    }

    interface ParticipantRepo extends JpaRepository<Participant, UUID> {
        List<Participant> findByRecordingIdOrderBySpeakerLabelAsc(UUID recordingId);
    }

    interface RecordingDocumentRepo extends JpaRepository<RecordingDocument, UUID> {
        List<RecordingDocument> findByRecordingIdOrderByCreatedAtAsc(UUID recordingId);
        long countByRecordingId(UUID recordingId);

        /** Nach einem Neustart haengengebliebene Extraktionen (Status PENDING). */
        List<RecordingDocument> findByStatus(RecordingDocument.Status status);
    }

    interface SummaryRepo extends JpaRepository<Summary, UUID> {
        List<Summary> findByRecordingIdOrderByCreatedAtDesc(UUID recordingId);

        /** Die aktuelle Fassung; der Teil-Index uq_summary_current haelt sie eindeutig. */
        Optional<Summary> findByRecordingIdAndCurrentIsTrue(UUID recordingId);

        /**
         * Aufnahmen, deren Zusammenfassung den Suchbegriff enthaelt (Kleinschreibung,
         * %...%). Gesucht wird nur in der aktuellen Fassung - ein Treffer soll in dem
         * Text stehen, den die Aufnahme auch anzeigt, nicht in einer verworfenen Fassung.
         */
        @Query("""
            select distinct s.recordingId from Summary s
              where s.current = true and s.markdown is not null
                and lower(s.markdown) like :pattern escape '!'
            """)
        List<UUID> findRecordingIdsByMarkdownLike(@Param("pattern") String pattern);
    }

    interface ShareGrantRepo extends JpaRepository<ShareGrant, UUID> {
        List<ShareGrant> findByRecordingId(UUID recordingId);
        boolean existsByRecordingIdAndGranteeUserId(UUID recordingId, UUID userId);
        boolean existsByRecordingIdAndGranteeGroupId(UUID recordingId, UUID groupId);

        @Query("""
            select count(s) > 0 from ShareGrant s where s.recordingId = :recordingId and
              (s.granteeUserId = :userId
               or s.granteeGroupId in (select m.groupId from GroupMember m where m.userId = :userId)
               or s.granteeGroupId in (select g.id from UserGroup g where g.ownerId = :userId))
            """)
        boolean hasAccess(@Param("recordingId") UUID recordingId, @Param("userId") UUID userId);
    }

    interface ShareLinkRepo extends JpaRepository<ShareLink, UUID> {
        List<ShareLink> findByRecordingIdOrderByCreatedAtDesc(UUID recordingId);
        Optional<ShareLink> findByToken(String token);
        long countByRecordingId(UUID recordingId);
    }

    interface ProcessingJobRepo extends JpaRepository<ProcessingJob, UUID> {
        List<ProcessingJob> findByStatusOrderByCreatedAt(ProcessingJob.Status status);
        List<ProcessingJob> findByRecordingIdOrderByCreatedAtDesc(UUID recordingId);
        boolean existsByRecordingIdAndStatusIn(UUID recordingId, List<ProcessingJob.Status> statuses);
    }

    interface PromptTemplateRepo extends JpaRepository<PromptTemplate, UUID> {
        List<PromptTemplate> findByOwnerIdOrderByNameAsc(UUID ownerId);
        long countByOwnerId(UUID ownerId);
        boolean existsByOwnerIdAndNameIgnoreCase(UUID ownerId, String name);
    }

    interface GlossaryEntryRepo extends JpaRepository<GlossaryEntry, UUID> {
        List<GlossaryEntry> findByOwnerIdOrderByTermKeyAsc(UUID ownerId);
        long countByOwnerId(UUID ownerId);
        Optional<GlossaryEntry> findByOwnerIdAndTermKey(UUID ownerId, String termKey);

        // Gemeinsames Glossar der Installation. Eigene Methoden, weil ein
        // uebergebenes null zu "owner_id = null" wird und damit nie trifft.
        List<GlossaryEntry> findByOwnerIdIsNullOrderByTermKeyAsc();
        long countByOwnerIdIsNull();
        Optional<GlossaryEntry> findByOwnerIdIsNullAndTermKey(String termKey);
    }

    interface AppSettingRepo extends JpaRepository<AppSetting, String> {
    }
}
