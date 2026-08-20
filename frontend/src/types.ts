// API-Typen des Meeting-Recorder-Backends

export interface UserView {
  id: string;
  username: string;
  displayName: string;
  email: string | null;
  admin: boolean;
  /** true = lokales Passwort-Konto (kann Passwort aendern); false = reines LDAP-Konto */
  local: boolean;
  mustChangePassword: boolean;
  /** Am Konto gespeicherte Oberflächensprache; null = noch nicht gewählt */
  language: string | null;
}

/** Laufende Aufnahme eines Nutzers in der Admin-Übersicht. */
export interface ActiveRecordingView {
  id: string;
  title: string | null;
  status: 'RECORDING' | 'FINALIZING';
  source: RecordingSource;
  startedAt: string;
}

/**
 * Nutzer in der Admin-Verwaltung: zusätzlich zum {@link UserView} der
 * Aktivitätszustand und die gerade laufenden Aufnahmen.
 */
export interface AdminUserView extends UserView {
  lastLoginAt: string | null;
  /** Letzte Anfrage aus dem Frontend; null = seit Einführung nicht gesehen. */
  lastSeenAt: string | null;
  /** Serverseitig bewertet: Aktivität innerhalb des Online-Fensters. */
  online: boolean;
  activeRecordings: ActiveRecordingView[];
}

export interface AuthConfig {
  'auth.ldapEnabled': string;
  'auth.ldapDomain': string;
  'auth.ldapUrl': string;
  'auth.ldapRootDn': string;
  'auth.bootstrapAdmins': string;
}

export interface LdapTestResult {
  success: boolean;
  message: string;
  displayName: string | null;
  email: string | null;
}

export type BotStatus =
  | 'STARTING'
  | 'JOINED'
  | 'RECORDING'
  | 'RECONNECTING'
  | 'STOPPED'
  | 'FAILED';

export interface BotView {
  sessionId: string;
  status: BotStatus;
  meetingUrl: string;
  /** Vom Bot aus der BBB-Oberfläche erkannter Raumname (kann kurz nach dem Join noch fehlen) */
  roomName: string | null;
  botName: string;
  autoRecord: boolean;
  recordVideo: boolean;
  aiAnalysis: boolean;
  recordingId: string | null;
  participants: number;
  audioTracks: number;
  lastError: string | null;
  createdAt: string;
  mine: boolean;
}

export interface CreateBotRequest {
  meetingUrl: string;
  botName?: string;
  autoRecord?: boolean;
  recordVideo?: boolean;
  aiAnalysis?: boolean;
  /** Sprechererkennung – nur wirksam, wenn der Admin sie freigeschaltet hat. */
  diarize?: boolean;
  /**
   * Sprache der Spracherkennung für die Aufnahmen dieses Bots; leer/undefined =
   * Admin-Standard, `auto` = Whisper erkennt sie selbst.
   */
  sttLanguage?: string | null;
}

export interface BotSessionHistoryView {
  id: string;
  meetingUrl: string;
  roomName: string | null;
  botName: string;
  status: BotStatus;
  createdAt: string;
  endedAt: string | null;
  lastError: string | null;
}

export type RecordingStatus =
  | 'RECORDING'
  | 'FINALIZING'
  | 'RECORDED'
  | 'PROCESSING'
  | 'TRANSCRIBED'
  | 'DONE'
  | 'FAILED'
  | 'DISCARDED';

export type VideoStatus = 'NONE' | 'RECORDING' | 'MUXING' | 'READY' | 'FAILED';

/** Woher eine Aufnahme stammt. */
export type RecordingSource = 'BOT' | 'UPLOAD' | 'CAPTURE';

/** Schlagwort mit Anzahl der Aufnahmen (Filterleiste, Vorschlagsliste). */
export interface TagCountView {
  name: string;
  count: number;
}

export interface RecordingView {
  id: string;
  title: string | null;
  status: RecordingStatus;
  meetingUrl: string;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  discardReason: string | null;
  recordVideo: boolean;
  aiAnalysis: boolean;
  videoStatus: VideoStatus | null;
  /** Herkunft der Aufnahme: Bot im Meeting, Datei-Upload oder Bildschirmaufnahme */
  source: RecordingSource;
  /** Schlagworte der Aufnahme (Anzeigeform, alphabetisch) */
  tags: string[];
  mine: boolean;
  owner: UserView | null;
}

export type SegmentStatus =
  | 'RECORDING'
  | 'TRANSCODING'
  | 'READY'
  | 'EMPTY'
  | 'FAILED';

export interface SegmentView {
  id: string;
  seq: number;
  status: SegmentStatus;
  durationMs: number | null;
  sizeBytes: number | null;
  hasAudio: boolean;
  hasTranscript: boolean;
}

/** Eintrag des zusammengeführten Transkripts (Startzeit in Sekunden ab Aufnahmebeginn). */
export interface TranscriptEntry {
  startSeconds: number;
  speaker: string | null;
  text: string;
}

/** Zustand der KI-Glättung des Transkripts. */
export type CorrectionStatus = 'NONE' | 'READY' | 'FAILED';

export interface TranscriptView {
  /** Unverändertes Whisper-Ergebnis. */
  transcript: string;
  entries: TranscriptEntry[];
  /** KI-geglättete Fassung; null, wenn keine existiert. */
  correctedTranscript: string | null;
  correctedEntries: TranscriptEntry[];
  hasCorrected: boolean;
  correctionStatus: CorrectionStatus | null;
}

/**
 * Geltungsbereich eines Glossars: die eigene Liste oder die gemeinsame der
 * Installation (gepflegt von Admins, gelesen von allen).
 */
export type GlossaryScope = 'personal' | 'shared';

/** Eintrag im Glossar (Abkürzung/Fachbegriff). */
export interface GlossaryEntryView {
  id: string;
  term: string;
  meaning: string | null;
  createdAt: string;
  updatedAt: string | null;
}

/**
 * API-Schlüssel in der Übersicht. Das Token selbst ist hier bewusst nicht
 * enthalten – gespeichert ist nur sein Abdruck, angezeigt wird `prefix`.
 */
export interface ApiKeyView {
  id: string;
  name: string;
  prefix: string;
  readOnly: boolean;
  createdAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  expired: boolean;
}

/** Antwort beim Anlegen: `token` ist nur in dieser einen Antwort enthalten. */
export interface ApiKeyCreated {
  key: ApiKeyView;
  token: string;
}

/** Ergebnis eines Glossar-Imports: was die CSV-Datei bewirkt hat. */
export interface GlossaryImportResult {
  created: number;
  updated: number;
  unchanged: number;
  /** Zeilen der Datei, die nicht übernommen wurden (siehe warnings). */
  skipped: number;
  /** Hinweise mit Zeilennummer, serverseitig auf 50 Einträge gekürzt. */
  warnings: string[];
}

/** Ergebnis eines Verbindungstests (Whisper/LLM) im Admin-Bereich. */
export interface ConnectionTestResult {
  success: boolean;
  message: string;
  durationMs: number;
}

export type ProcessStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED';

/**
 * Eine Fassung der Zusammenfassung. Jede Auswertung legt eine weitere an; genau
 * eine ist die aktuelle (`current`) und gilt überall als „die" Zusammenfassung.
 */
export interface SummaryView {
  id: string;
  status: ProcessStatus;
  markdown: string | null;
  model: string | null;
  /** Temperatur dieser Fassung; gehört zum Modell (null = unbekannt/älter). */
  temperature: number | null;
  /** Vorlage, mit der die Fassung erzeugt wurde (null = keine benannte). */
  templateName: string | null;
  /** Auswertungs-Prompt dieser Fassung – damit sich zwei Fassungen erklären lassen. */
  systemPrompt: string | null;
  current: boolean;
  error: string | null;
  createdAt: string;
  finishedAt: string | null;
  /** Zeitpunkt der letzten händischen Bearbeitung (null = unberührt vom Modell). */
  editedAt: string | null;
}

export interface JobView {
  id: string;
  status: ProcessStatus;
  immediate: boolean;
  /** true = nur Transkription (Schritt 1 der Zwei-Schritt-Auswertung) */
  transcribeOnly: boolean;
  attempts: number;
  lastError: string | null;
  createdAt: string;
  finishedAt: string | null;
}

/**
 * Pro-Aufnahme-Einstellungen für Spracherkennung und Zusammenfassung
 * (null = Admin-Standard).
 */
export interface SummaryOptionsView {
  prompt: string | null;
  /** Name der gewählten Vorlage; benennt später die erzeugte Fassung. */
  templateName: string | null;
  maxWords: number | null;
  /** Sprache der Zusammenfassung. */
  language: string | null;
  /** Sprache der Spracherkennung; `auto` = Whisper erkennt sie selbst. */
  sttLanguage: string | null;
  /** Modell für diese Aufnahme (null = Admin-Vorgabe). */
  model: string | null;
  /** Temperatur für diese Aufnahme (null = Admin-Vorgabe). */
  temperature: number | null;
  /** Admin-Standardwerte, damit die UI zeigen kann, was "Standard" bedeutet. */
  defaultPrompt: string;
  defaultLanguage: string;
  /** Admin-Standard der Spracherkennung; leer bedeutet "automatisch erkennen". */
  defaultSttLanguage: string;
  defaultModel: string;
  defaultTemperature: number;
}

/** Teilnehmer einer Aufnahme: aus der Diarisierung erkannter Sprecher mit editierbarem Namen. */
export interface ParticipantView {
  id: string;
  /** Rohes Diarisierungs-Label (z.B. SPEAKER_00), Zuordnung zum Transkript */
  speakerLabel: string | null;
  displayName: string;
}

export interface RecordingDetail {
  recording: RecordingView;
  segments: SegmentView[];
  summaries: SummaryView[];
  jobs: JobView[];
  participants: ParticipantView[];
  participantsLog: string | null;
  chatLog: string | null;
  summaryOptions: SummaryOptionsView;
}

/**
 * Persönliche Promptvorlage des angemeldeten Nutzers – optional mit eigenem
 * Modell und eigener Temperatur (null = Admin-Vorgabe verwenden).
 */
export interface PromptTemplateView {
  id: string;
  name: string;
  prompt: string;
  model: string | null;
  temperature: number | null;
  createdAt: string;
  updatedAt: string | null;
}

export interface GroupView {
  id: string;
  name: string;
  ownerId: string;
  mine: boolean;
  createdAt: string;
}

export interface GroupMemberView {
  userId: string;
  username: string;
  displayName: string;
  addedAt: string;
}

export interface ShareView {
  id: string;
  recordingId: string;
  user: UserView | null;
  group: GroupView | null;
  createdAt: string;
}

/**
 * Öffentlicher Freigabe-Link. Die vollständige Adresse wird im Frontend aus
 * `token` gebildet (siehe `shareLinkUrl`) – der Server kennt seine eigene
 * öffentliche Adresse nicht.
 */
export interface ShareLinkView {
  id: string;
  token: string;
  createdAt: string;
  /** null = gültig bis zum Widerruf */
  expiresAt: string | null;
  expired: boolean;
  views: number;
  lastViewedAt: string | null;
  /**
   * true = Empfänger muss sich anmelden und bekommt die Aufnahme dabei
   * freigegeben. Kann auch von der Admin-Einstellung kommen (Zugriff ohne
   * Anmeldung installationsweit abgeschaltet).
   */
  requiresLogin: boolean;
}

/** Ergebnis des Einlösens eines Freigabe-Links durch einen angemeldeten Nutzer. */
export interface ShareLinkClaimView {
  recordingId: string;
  title: string | null;
  /** true = die Aufnahme wurde dabei neu mit dem Konto geteilt */
  shared: boolean;
}

/** Abspielbares Audio-Segment in der Freigabe-Ansicht. */
export interface PublicSegmentView {
  id: string;
  seq: number;
  durationMs: number | null;
  sizeBytes: number | null;
}

/**
 * Inhalt der öffentlichen Freigabe-Ansicht: Video, Audio, Transkript und
 * Zusammenfassung. Chat- und Sitzungsprotokoll sind bewusst nicht enthalten.
 */
export interface PublicShareView {
  title: string | null;
  startedAt: string;
  endedAt: string | null;
  durationMs: number | null;
  source: RecordingSource;
  /** Anzeigename des Besitzers, der die Aufnahme freigegeben hat. */
  sharedBy: string | null;
  hasVideo: boolean;
  segments: PublicSegmentView[];
  summary: string | null;
  summaryCreatedAt: string | null;
  transcript: string;
  entries: TranscriptEntry[];
  participants: ParticipantView[];
  /** null = der Link gilt bis zum Widerruf */
  expiresAt: string | null;
  /** Oberflächensprache des Freigebenden; null = nie gewählt */
  language: string | null;
}

export interface LoginResponse {
  token: string;
  user: UserView;
}
