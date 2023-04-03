package com.tc.uploader;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.google.api.services.youtube.model.VideoSnippet;
import com.google.api.services.youtube.model.VideoStatus;

import java.io.*;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;

public class Uploader {

    /** TC Uploader version (major.minor.patch). */
    private static final String VERSION = "2.0.2";

    /** Directory to watch for completed render/preview. */
    public static final String WATCH_DIR = System.getProperty("user.home") + "\\TC\\Project Files";

    /** Directory to store render after it is uploaded. */
    private static final String VIDEOS_DIR = System.getProperty("user.home") + "\\TC\\Videos";
    
    /** Name of completed render. */
    private static final String RENDER_FILE_NAME = "Render.mp4";

    /** Name of completed preview */
    private static final String PREVIEW_FILE_NAME = "Preview.mp4";

    /** Name of completed and synced preview */
    public static final String PREVIEW_SYNCED_FILE_NAME = "out.mp4";

    /** Hour (UTC-24) to upload render. */
    private static final int HOUR = 17;

    /** Delay (ms) between polling for new changes in WATCH_DIR */
    private static final long WATCH_DELAY = 5000;

    /** Time (ms) the completed render must be unmodified for before allowing input and upload. */
    private static final long UNMODIFIED_TIME = 5000;

    /** Time (ms) before checking how long a completed render has been unmodified for again. */
    private static final long SLEEP_TIME = 5000;

    /** Application name. */
    private static final String APPLICATION_NAME = "RenderVM Uploader";

    /** Directory to store user credentials (and ucid). */
    public static java.io.File DATA_STORE_DIR;

    /** Global instance of the {@link FileDataStoreFactory}. */
    private static FileDataStoreFactory DATA_STORE_FACTORY;

    /** Global instance of the JSON factory. */
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();

    /** Global instance of the HTTP transport. */
    private static HttpTransport HTTP_TRANSPORT;

    private static final Collection<String> SCOPES = Collections.singletonList("https://www.googleapis" + ".com/auth/youtube.force-ssl");

    private static final Timer TIMER = new Timer();
    private static final Scanner SCANNER = new Scanner(System.in);
    private static YouTube youtube;

    static {
        try {
            DATA_STORE_DIR = new File(Uploader.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
            HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

            final Logger fileDataStoreFactoryLogger = Logger.getLogger(FileDataStoreFactory.class.getName());

            fileDataStoreFactoryLogger.setLevel(java.util.logging.Level.SEVERE);
            DATA_STORE_FACTORY = new FileDataStoreFactory(DATA_STORE_DIR);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }

    public static void main(String[] args)  {
        try {
            TCAPI.getInstance().getMeta();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (true)
            return;

        File preview = new File(WATCH_DIR + File.separator + PREVIEW_FILE_NAME);

        // If Preview.mp4 exists, upload to ServerVM using an HTTP POST request.
        if (preview.exists()) {
            Log.info("Uploader#upload: Preview found at %s. Uploading.", WATCH_DIR + File.separator + PREVIEW_FILE_NAME);
            System.out.println("Preview found.");
            try {
                TCAPI.getInstance().uploadPreview(new File(WATCH_DIR + File.separator + PREVIEW_FILE_NAME), 10);
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else
            Log.error("Preview does not exist at %s", WATCH_DIR + File.separator + PREVIEW_FILE_NAME);

        if (true)
        return;

        Log.info("Uploader#main(): Started TC Uploader %s.", VERSION);

        try {
            youtube = getYouTubeService();
            watchDirectory();
        } catch (Exception e) {
            handleException(e);
        }
    }

    /** Watches the directory for the completed render. */
    private static void watchDirectory() throws Exception {
        Log.info("Uploader#watchDirectory(): Watching directory %s for %s.",  WATCH_DIR, RENDER_FILE_NAME);

        // Directory to watch for Render.mp4
        Path watchDirectory = Paths.get(WATCH_DIR);

        // Setup WatchService.
        WatchService watchService = FileSystems.getDefault().newWatchService();
        WatchKey watchKey = watchDirectory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        // Poll watchKey for new ENTRY_CREATE events.
        TIMER.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                for (WatchEvent<?> event : watchKey.pollEvents()) {
                    // Get file that was created.
                    Path path = watchDirectory.resolve((Path) event.context());
                    File file = path.toFile();

                    if (file.getName().equals(RENDER_FILE_NAME)) {
                        Log.info("Uploader#watchDirectory(): %s created in %s.", RENDER_FILE_NAME, WATCH_DIR);

                        // Stop polling for new files in WATCH_DIR.
                        this.cancel();

                        // If it has not been UNMODIFIED_TIME since Render.mp4 has been modified, wait SLEEP_TIME and
                        // check again.
                        while (System.currentTimeMillis() - file.lastModified() < UNMODIFIED_TIME) {
                            Log.info("Uploader#watchDirectiory(): %s has been modified in the last %s ms. Waiting %s ms.",
                                    RENDER_FILE_NAME, UNMODIFIED_TIME, SLEEP_TIME);

                            try {
                                Thread.sleep(SLEEP_TIME);
                            } catch (InterruptedException e) {
                                handleException(e);
                            }
                        }

                        // It has been UNMODIFIED_TIME since Render.mp4 has been modified. Start upload process.
                        Log.info("Uploader#watchDirectory(): %s is safe.", RENDER_FILE_NAME);
                        
                        // Communicate status messages with RenderVM worker.
                        try {
                            TCAPI.Status.READY.print();
                            Log.info("Uploader#watchDirectory(): Printed status %s", TCAPI.Status.READY.getName());
                            awaitReturn();
                            cls();
                            TCAPI.Status.UPLOADING.print();
                            Log.info("Uploader#watchDirectory(): Printed status %s", TCAPI.Status.UPLOADING.getName());
                            upload(file);
                            cls();
                            TCAPI.Status.DONE.print();
                            Log.info("Uploader#watchDirectory(): Printed status %s", TCAPI.Status.DONE.getName());
                            awaitReturn();
                            cls();
                            Log.info("Uploader#watchDirectory(): Uploader complete. Restarting.");
                            watchDirectory();
                        } catch (Exception e) {
                            handleException(e);
                        }
                    }
                }
            }
        }, 0L, WATCH_DELAY);
    }

    /** Uploads the render using YouTube API with the metadata from TCAPI, and uploads the Preview to ServerVM with TCAPI. */
    private static void upload(File file) throws Exception {

        // Get metadata of render (title, description, tags, and previewStart).
        TCAPI.RenderMeta meta = TCAPI.getInstance().getMeta();
        Optional<String> title = meta.getTitleValue();
        Optional<String> description = meta.getDescription();
        Optional<List<String>> tags = meta.getTags();
        int previewStart = meta.getPreviewStart();

        // Don't upload video if the metadata does not include title, description, and tags.
        final boolean titlePresent = title.isPresent();
        final boolean descriptionPresent = description.isPresent();
        final boolean tagsPresent = tags.isPresent();
        if (!(titlePresent && descriptionPresent && tagsPresent)) {
            Log.error("Uploader#upload: Metadata %s%s%s was not received, skipping upload.",
                    titlePresent ? "" : "title", descriptionPresent ? "" : "description",
                    tagsPresent ? "" : "tags");
            return;
        }

        // Create and set the Video's VideoSnippet (video category, title, description, tags).
        Video video = new Video();
        VideoSnippet snippet = new VideoSnippet();
        snippet.set("categoryId", "10"); // 10 = Music (videos uploaded via API do not use Creator Studio upload defaults).
        snippet.set("title", title.get());
        snippet.set("description", description.get());
        snippet.set("tags", tags.get());
        video.setSnippet(snippet);

        // Create and set the Video's VideoStatus (privacyStatus, publishAt).
        VideoStatus status = new VideoStatus();
        status.set("privacyStatus", "private");

        // Calculate time to schedule next TC upload.
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC); // Current time.
        ZonedDateTime todayUpload = now.withHour(HOUR).truncatedTo(ChronoUnit.HOURS); // Time video was/will be uploaded today.
        ZonedDateTime nextUpload = now.isBefore(todayUpload) ? todayUpload : todayUpload.plusDays(1); // The date/time of the next upload.
        String nextUploadFormatted = DateTimeFormatter.ISO_DATE_TIME.format(nextUpload); // The ISO 8601-compliant String of the next upload date/time.
        DateTime nextUploadGoogle = new DateTime(nextUploadFormatted); // The next upload date/time in a com.google.api.client.util.DateTime object.

        status.set("publishAt", nextUploadGoogle); // Schedule video.
        video.setStatus(status);

        // Create a YouTube insert request with the video and the metadata.
        InputStreamContent mediaContent = new InputStreamContent("video/*", new FileInputStream(file));
        YouTube.Videos.Insert videosInsertRequest = youtube.videos().insert("snippet,status", video, mediaContent);

        // Execute the insert request (upload video).
        Log.info("Uploading video %s scheduled for %s.", title.get(), nextUploadFormatted);
        videosInsertRequest.execute();
        Log.info("Uploaded %s.", title.get());

        // If Preview.mp4 exists, upload to ServerVM using an HTTP POST request.
        File preview = new File(WATCH_DIR + File.separator + PREVIEW_FILE_NAME);
        if (preview.exists()) {
            Log.info("Uploader#upload: Preview found at %s. Uploading.", WATCH_DIR + File.separator + PREVIEW_FILE_NAME);
            TCAPI.getInstance().uploadPreview(preview, previewStart);
        } else
            Log.error("Preview does not exist at %s", WATCH_DIR + File.separator + PREVIEW_FILE_NAME);

        // Delete preview and synced preview to prepare for next render.
        Files.delete(preview.toPath());
        Files.delete(new File(WATCH_DIR + File.separator + PREVIEW_SYNCED_FILE_NAME).toPath());

        // Archive video.
        Files.move(file.toPath(), new File(VIDEOS_DIR + File.separator + title.get() + ".mp4").toPath());
    }

    /** Pauses the Uploader until the RenderVM worker inputs the return key. */
    private static void awaitReturn() {
        Log.info("Uploader#awaitReturn(): Awaiting return key.");
        SCANNER.nextLine();
        Log.info("Uploader#awaitReturn(): return key received.");
    }

    /** Clears Uploader output; call on Windows 7 Proxmox VM */
    private static void cls() throws IOException, InterruptedException {
        if (System.getProperty("os.name").contains("Windows"))
            new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
        else Runtime.getRuntime().exec("clear");

        Log.info("Uploader#cls(): Cleared.");
    }

    /** Logs and prints exceptions, safely exits. */
    public static void handleException(Exception e) {
        Log.exception(e);
        e.printStackTrace();
        System.exit(1);
    }

    /** Creates an authorized Credential object. */
    private static Credential authorize() throws IOException {
        // Load client secrets.
        InputStream in = new FileInputStream(DATA_STORE_DIR.toPath() + "\\ucid.json");
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, clientSecrets, SCOPES).setDataStoreFactory(DATA_STORE_FACTORY).setAccessType("offline").build();
        return new AuthorizationCodeInstalledApp(flow, new LocalServerReceiver()).authorize("user");
    }

    /** Build and return an authorized YouTube API client service. */
    private static YouTube getYouTubeService() throws IOException {
        Credential credential = authorize();
        return new YouTube.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).setApplicationName(APPLICATION_NAME).build();
    }
}