package com.tc.uploader;

import com.google.gson.Gson;
import org.apache.http.HttpResponse;
import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public class TCAPI extends HttpAPI {

    private static TCAPI instance;

    private static final int TIMEOUT = 60000;

    /** The ServerVM URL to send a POST request to for uploading the Preview.mp4 */
    private static final String PREVIEW_URL = "/render/php/upload.php";

    /** The ServerVM URL to GET render metadata (title, description, tags) from. */
    private static final String META_URL = "/render/php/alive.php?status=META";

    private static final Gson GSON = new Gson();

    private TCAPI() {
        super(HttpClientBuilder.create().setConnectionTimeToLive(TIMEOUT, TimeUnit.NANOSECONDS).build());
    }

    /** A RenderVM status. */
    public enum Status {
        /** Render has completed and is ready to upload. */
        READY("READY"),

        /** Render and preview are uploading. */
        UPLOADING("UPLOADING"),

        /** Render and preview have finished uploading. */
        DONE("DONE");

        private String name;

        Status(String name) {
            this.name = name;
        }

        public void print() {
            System.out.println(name);
        }

        public String getName() {
            return name;
        }
    }

    /** The metadata of a RenderVM job. */
    public class RenderMeta {
        private String titleValue;
        private String description;
        private List<String> tags;
        private int previewStart;

        public RenderMeta(String titleValue, String description, List<String> tags, int previewStart) {
            this.titleValue = titleValue;
            this.description = description;
            this.tags = tags;
            this.previewStart = previewStart;
        }

        public Optional<String> getTitleValue() {
            return Optional.ofNullable(titleValue);
        }

        public Optional<String> getDescription() {
            return Optional.ofNullable(description);
        }

        public Optional<List<String>> getTags() {
            return Optional.ofNullable(tags);
        }

        public int getPreviewStart() {
            return previewStart;
        }

    }

    /** Gets the metadata of the currently rendering video from the ServerVM. */
    public RenderMeta getMeta() throws IOException {
        Log.info("TCAPI#getMeta(): Getting render metadata from %s.", META_URL);
        HttpResponse response;
        try {
            response = get(META_URL, TIMEOUT, TIMEOUT, TIMEOUT);
        } catch (ConnectTimeoutException e) {
            e.printStackTrace();
            return getMeta();
        }
        Log.info("TCAPI#getMeta(): Received metadata response: %s", response.getStatusLine());
        String json = EntityUtils.toString(response.getEntity());
        Log.info("TCAPI#getMeta(): Received metadata: %s", json);
        return GSON.fromJson(json, RenderMeta.class);
    }

    /** Uploads the rendered Preview to the ServerVM at /render/Preview.mp4. */
    public void uploadPreview(File preview, int previewStart) throws IOException {
        File previewSynced = syncPreviewAudio(preview, previewStart);
        postFile("upload", previewSynced, PREVIEW_UR L);
    }

    /** Uses ffmpeg to sync and embed the audio track based on the preview's start time. */
    private File syncPreviewAudio(File preview, int previewStart) throws IOException {
        Log.info("TCAPI#syncPreviewAudio(): Syncing preview audio, previewStart = %s.", previewStart);
        File syncedPreview = new File(Uploader.WATCH_DIR + File.separator + Uploader.PREVIEW_SYNCED_FILE_NAME);
        Process process = Runtime.getRuntime().exec(String.format("ffmpeg -y -i %s -itsoffset -%s -i music.mp3 -af " +
                "\"afade=t=out:st=25:d=5\" -map 0:v -map 1:a -shortest %s", preview.getPath(), previewStart, syncedPreview.getPath()));
        return syncedPreview;
    }

    public static TCAPI getInstance() {
        if (instance == null) {
            instance = new TCAPI();
        }

        return instance;
    }
}