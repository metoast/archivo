/*
 * Copyright 2015-2016 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.archivo.controller;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import net.straylightlabs.archivo.Archivo;
import net.straylightlabs.archivo.model.*;
import net.straylightlabs.archivo.net.MindCommandIdSearch;
import net.straylightlabs.archivo.utilities.OSHelper;
import net.straylightlabs.tivolibre.TivoDecoder;
import org.apache.http.Header;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CookieStore;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;

/**
 * Handle the tasks of fetching the recording file from a TiVo, decrypting it, and transcoding it.
 */
class ArchiveTask extends Task<Recording> {
    private final Recording recording;
    private final Tivo tivo;
    private final String mak;
    private final UserPrefs prefs;
    private final Lock downloadLock;
    private final Lock processingLock;
    private Path downloadPath; // downloaded file
    private Path encryptedPath; // for debugging, the original encrypted file
    private Path fixedPath; // re-muxed file
    private Path ffsplitPath; // FFSkip file from Comskip
    private Path cutPath; // file with commercials removed
    private Path metadataPath; // PyTivo metadata file
    private long downloadDurationMS;
    private long processingDurationMS;
    private final boolean keepEncryptedFile;

    private final static Logger logger = LoggerFactory.getLogger(ArchiveTask.class);

    private static final int BUFFER_SIZE = 8192; // 8 KB
    private static final int PIPE_BUFFER_SIZE = 1024 * 1024 * 16; // 16 MB
    private static final int MIN_PROGRESS_INCREMENT = 10 * 1024 * 1024; // 10 MB, number of bytes that must transfer before we update our progress
    private static final int NUM_RETRIES = 5; // number of times to retry a failed download
    private static final int RETRY_DELAY = 30; // delay between retry attempts, in seconds
    private static final int RETRY_MULTIPLIER = 2; // Multiplier for increasing the delay between retry attempts
    private static final double ESTIMATED_SIZE_THRESHOLD = 0.8; // Need to download this % of a file to consider it successful

    private static final int MS_PER_SECOND = 1000;
    private static final int PAUSE_AFTER_DOWNLOAD = 5 * MS_PER_SECOND;

    ArchiveTask(Recording recording, Tivo tivo, String mak, final UserPrefs prefs,
                final Lock downloadLock, final Lock processingLock) {
        this.recording = recording;
        this.tivo = tivo;
        this.mak = mak;
        this.prefs = prefs;
        this.downloadLock = downloadLock;
        this.processingLock = processingLock;
        this.keepEncryptedFile = prefs.getDebugMode();
    }

    public Recording getRecording() {
        return recording;
    }

    @Override
    protected Recording call() throws ArchiveTaskException {
        archive();
        int downloadMins = (int) (downloadDurationMS / 1000 / 60);
        int processingMins = (int) (processingDurationMS / 1000 / 60);
        Archivo.telemetryController.sendArchivedEvent(downloadMins, processingMins, isCancelled());
        return recording;
    }

    private void archive() throws ArchiveTaskException {
        try {
            logger.debug("LOCKING DOWNLOAD");
            downloadLock.lock();
            logger.debug("DOWNLOAD LOCKED");
            if (isCancelled()) {
                logger.info("ArchiveTask canceled by user.");
                return;
            } else if (!Files.isDirectory(recording.getDestination().getParent())) {
                try {
                    Files.createDirectories(recording.getDestination().getParent());
                } catch (IOException e) {
                    logger.error("Destination folder ({}) no longer exists", recording.getDestination().getParent());
                    throw new ArchiveTaskException("Destination folder no longer exists");
                }
            }
            logger.info("Starting archive task for {}", recording.getTitle());
            Platform.runLater(() -> recording.setStatus(
                    ArchiveStatus.createConnectingStatus(0, 0, NUM_RETRIES)
            ));
            MindCommandIdSearch command = new MindCommandIdSearch(recording, tivo);
//            command.setCompatibilityMode(true); // Download a PS file instead of a TS file
            command.executeOn(tivo.getClient());
            URL url = command.getDownloadUrl();
            logger.info("URL: {}", url);
            setupPaths();
            getRecording(url);
            try {
                // Give TiVo's HTTP server a chance to get ready for the next request, otherwise it will usually fail
                Thread.sleep(PAUSE_AFTER_DOWNLOAD);
            } catch (InterruptedException e) {
                // Ignore this
            }
        } catch (IOException e) {
            logger.error("Error fetching recording information: ", e);
            throw new ArchiveTaskException("Problem fetching recording information");
        } finally {
            downloadLock.unlock();
        }
        try {
            Platform.runLater(() -> recording.setStatus(
                    ArchiveStatus.DOWNLOADED
            ));
            logger.debug("LOCKING PROCESS");
            processingLock.lock();
            logger.debug("PROCESS LOCKED");
            if (isCancelled()) {
                logger.info("ArchiveTask canceled by user.");
                cleanupIntermediateFiles();
                return;
            }
            long processingStartTime = System.currentTimeMillis();
            if (shouldDecrypt(recording)) {
                remux();
                if (prefs.getSkipCommercials()) {
                    detectCommercials();
                    cutCommercials();
                }
                if (recording.getDestinationType().needsTranscoding()) {
                    transcode();
                } else {
                    cleanupFiles(recording.getDestination());
                    if (prefs.getSkipCommercials()) {
                        Files.move(cutPath, downloadPath);
                        remux();
                    }
                    Files.move(fixedPath, recording.getDestination());
                }
                cleanupFiles(fixedPath, downloadPath);
            } else {
                cleanupFiles(recording.getDestination());
                Files.move(downloadPath, recording.getDestination());
            }
            processingDurationMS = System.currentTimeMillis() - processingStartTime;
        } catch (IOException e) {
            logger.error("Error fetching recording information: ", e);
            throw new ArchiveTaskException("Problem fetching recording information");
        } finally {
            processingLock.unlock();
        }
    }

    private void setupPaths() {
        downloadPath = buildPath(recording.getDestination(), "download.ts");
        encryptedPath = buildPath(recording.getDestination(), "TiVo");
        fixedPath = buildPath(recording.getDestination(), "fixed.ts");
        cutPath = buildPath(recording.getDestination(), "cut.ts");
        metadataPath = buildPath(recording.getDestination(), "ts.txt");
        logger.info("Saving file to {}", downloadPath);
    }

    private void getRecording(URL url) throws ArchiveTaskException {
        if (isCancelled()) {
            logger.info("ArchiveTask canceled by user.");
            return;
        }

        long downloadStartTime = System.currentTimeMillis();
        try (CloseableHttpClient client = buildHttpClient()) {
            HttpGet get = new HttpGet(url.toString());
            // Initial request to set the session cookie
            try (CloseableHttpResponse response = client.execute(get)) {
                response.close(); // Not needed, but clears up a warning
            }
            // Now fetch the file
            int retries = NUM_RETRIES;
            int retryDelay = RETRY_DELAY;
            boolean responseOk = false;
            while (!responseOk && retries > 0) {
                try (CloseableHttpResponse response = client.execute(get)) {
                    if (response.getStatusLine().getStatusCode() != 200) {
                        logger.error("Error downloading recording: {}", response.getStatusLine());
                        logger.info("Sleeping for {} ms", retryDelay * MS_PER_SECOND);
                        int failures = NUM_RETRIES - retries + 1;
                        ArchiveStatus status = ArchiveStatus.createConnectingStatus(retryDelay, failures, retries);
                        Platform.runLater(() -> recording.setStatus(status));
                        Thread.sleep(retryDelay * MS_PER_SECOND);
                        retryDelay *= RETRY_MULTIPLIER;
                        retries--;
                    } else {
                        logger.info("Status line: {}", response.getStatusLine());
                        responseOk = true;
                        handleResponse(response, recording);
                    }
                } catch (InterruptedException e) {
                    logger.error("Thread interrupted: ", e);
                }
            }
            if (!responseOk) {
                throw new ArchiveTaskException("Problem downloading recording", "Your TiVo is too busy to connect to. You can try again later or restart your TiVo.");
            }
        } catch (IOException e) {
            logger.error("Error downloading recording: ", e);
            throw new ArchiveTaskException("Problem downloading recording");
        }
        downloadDurationMS = System.currentTimeMillis() - downloadStartTime;
    }

    private CloseableHttpClient buildHttpClient() {
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
                new UsernamePasswordCredentials("tivo", mak));
        CookieStore cookieStore = new BasicCookieStore();
        ConnectionConfig connConfig = ConnectionConfig.custom().setBufferSize(BUFFER_SIZE).build();
        return HttpClients.custom()
                .useSystemProperties()
                .setDefaultConnectionConfig(connConfig)
                .setDefaultCredentialsProvider(credsProvider)
                .setDefaultCookieStore(cookieStore)
                .setUserAgent(Archivo.USER_AGENT)
                .build();
    }

    private void handleResponse(CloseableHttpResponse response, Recording recording) throws ArchiveTaskException {
        long estimatedLength = getEstimatedLengthFromHeaders(response);
        boolean decrypt = shouldDecrypt(recording);

        cleanupFiles(downloadPath);
        try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(downloadPath));
             BufferedInputStream inputStream = new BufferedInputStream(response.getEntity().getContent(), BUFFER_SIZE);
             PipedInputStream pipedInputStream = new PipedInputStream(PIPE_BUFFER_SIZE);
             PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream)
        ) {
            logger.info("Starting download...");
            Thread thread = null;
            if (decrypt && !keepEncryptedFile) {
                // Pipe the network stream to our TiVo decoder, then pipe the output of that to the output file stream
                thread = new Thread(() -> {
                    runDecoder(pipedInputStream, outputStream);
                });
                thread.start();
            }

            byte[] buffer = new byte[BUFFER_SIZE + 1];
            long totalBytesRead = 0;
            long priorBytesRead = 0;
            LocalDateTime startTime = LocalDateTime.now();
            for (int bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE);
                 bytesRead >= 0;
                 bytesRead = inputStream.read(buffer, 0, BUFFER_SIZE)) {
                if (decrypt && !keepEncryptedFile && !thread.isAlive()) {
                    logger.error("Decoding thread died prematurely");
                    response.close();
                    cleanupIntermediateFiles();
                    throw new ArchiveTaskException("Problem decoding recording");
                }
                if (isCancelled()) {
                    logger.info("ArchiveTask cancelled by user.");
                    response.close(); // Stop the network transaction
                    cleanupIntermediateFiles();
                    return;
                }
                totalBytesRead += bytesRead;
                logger.trace("Bytes read: {}", bytesRead);

                if (decrypt && !keepEncryptedFile) {
                    pipedOutputStream.write(buffer, 0, bytesRead);
                } else {
                    outputStream.write(buffer, 0, bytesRead);
                }

                if (totalBytesRead > priorBytesRead + MIN_PROGRESS_INCREMENT) {
                    double percent = totalBytesRead / (double) estimatedLength;
                    updateProgress(recording, percent, startTime, totalBytesRead, estimatedLength);
                    priorBytesRead = totalBytesRead;
                    logger.trace("Total bytes read from network: {}", totalBytesRead);
                }
            }
            logger.info("Download finished.");

            if (decrypt) {
                if (keepEncryptedFile) {
                    decryptRecording();
                } else {
                    // Close the pipe to ensure the decoding thread finishes
                    pipedOutputStream.flush();
                    pipedOutputStream.close();
                    // Wait for the decoding thread to finish
                    thread.join();
                    logger.info("Decoding finished.");
                }
            }

            verifyDownloadSize(totalBytesRead, estimatedLength);
        } catch (IOException e) {
            logger.error("IOException while downloading recording: ", e);
            throw new ArchiveTaskException("Problem downloading recording");
        } catch (InterruptedException e) {
            logger.error("Decoding thread interrupted: ", e);
        }
    }

    /**
     * If the user chose to save the file as a .TiVo, don't decrypt it.
     */
    private boolean shouldDecrypt(Recording recording) {
        boolean decrypt = !recording.getDestination().toString().endsWith(".TiVo");
        logger.info("decrypt = {}", decrypt);
        return decrypt;
    }

    private long getEstimatedLengthFromHeaders(CloseableHttpResponse response) {
        long length = -1;
        for (Header header : response.getAllHeaders()) {
            if (header.getName().equalsIgnoreCase("TiVo-Estimated-Length")) {
                try {
                    length = Long.parseLong(header.getValue());
                } catch (NumberFormatException e) {
                    logger.error("Error parsing estimated length ({}): ", header.getValue(), e);
                }
            }
        }
        return length;
    }

    private void updateProgress(Recording recording, double percent, LocalDateTime startTime, long totalBytesRead, long estimatedLength) {
        Duration elapsedTime = Duration.between(startTime, LocalDateTime.now());
        try {
            double kbs = (totalBytesRead / 1024) / elapsedTime.getSeconds();
            long kbRemaining = (estimatedLength - totalBytesRead) / 1024;
            int secondsRemaining = (int) (kbRemaining / kbs);
            logger.trace(String.format("Read %d bytes of %d expected bytes (%d%%) in %s (%.1f KB/s)",
                    totalBytesRead, estimatedLength, (int) (percent * 100), elapsedTime, kbs));
            Platform.runLater(() -> recording.setStatus(
                    ArchiveStatus.createDownloadingStatus(percent, secondsRemaining, kbs)
            ));
        } catch (ArithmeticException e) {
            logger.warn("ArithmeticException: ", e.getLocalizedMessage());
        }
    }

    private void verifyDownloadSize(long bytesRead, long bytesExpected) {
        if (bytesRead / (double) bytesExpected < ESTIMATED_SIZE_THRESHOLD) {
            logger.error("Failed to download file ({} bytes read, {} bytes expected)", bytesRead, bytesExpected);
            throw new ArchiveTaskException("Failed to download recording");
        }
    }

    private void decryptRecording() {
        try {
            Files.move(downloadPath, encryptedPath);
            try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(downloadPath));
                 BufferedInputStream inputStream = new BufferedInputStream(Files.newInputStream(encryptedPath))) {
                logger.info("Decrypting file...");
                runDecoder(inputStream, outputStream);
                logger.info("Decoding finished.");
            }
        } catch (IOException e) {
            logger.error("Could not rename {} to {}: {}", downloadPath, encryptedPath, e.getLocalizedMessage());
            cleanupIntermediateFiles();
            throw new ArchiveTaskException("Failed to rename recording");
        }
    }

    private void runDecoder(InputStream inputStream, OutputStream outputStream) {
        TivoDecoder decoder = new TivoDecoder.Builder().input(inputStream).output(outputStream)
                .mak(mak).compatibilityMode(false).build();
        if (!decoder.decode()) {
            logger.error("Failed to decode file");
            cleanupIntermediateFiles();
            throw new ArchiveTaskException("Problem decoding recording");
        }
        if (recording.getDestinationType().includeMetadata()) {
            logger.info("Saving metadata to '{}'", metadataPath);
            decoder.saveMetadata(metadataPath);
        }
    }

    /**
     * TiVo files often have timestamp problems. Remux to fix them.
     */
    private void remux() {
        Platform.runLater(() -> recording.setStatus(
                ArchiveStatus.createRemuxingStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        String ffmpegPath = prefs.getFFmpegPath();
        cleanupFiles(fixedPath);
        logger.info("ffmpeg path = {} outputPath = {}", ffmpegPath, fixedPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-fflags");
        cmd.add("+genpts+discardcorrupt+sortdts");
        cmd.add("-i");
        cmd.add(downloadPath.toString());
        cmd.add("-codec");
        cmd.add("copy");
        cmd.add("-avoid_negative_ts");
        cmd.add("make_zero");
        cmd.add("-seek2any");
        cmd.add("1");
        cmd.add("-f");
        cmd.add("mpegts");
        cmd.add(fixedPath.toString());
        try {
            FFmpegOutputReader outputReader = new FFmpegOutputReader(recording, ArchiveStatus.TaskStatus.REMUXING);
            if (!runProcess(cmd, outputReader)) {
                logger.error("FFmpeg error: {}", outputReader.getOutput());
                cleanupIntermediateFiles();
                throw new ArchiveTaskException("Error repairing video");
            } else {
                logger.debug("FFmpeg output: {}", outputReader.getOutput());
            }
        } catch (InterruptedException | IOException e) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText(String.format("Error running '%s': %s\n\nWorking directory: '%s'",
                        cmd.stream().collect(Collectors.joining(" ")), e.getLocalizedMessage(),
                        System.getProperty("user.dir")));
                alert.showAndWait();
            });
            logger.error("Error running ffmpeg to remux download: ", e);
            cleanupIntermediateFiles();
            throw new ArchiveTaskException("Error repairing video");
        } finally {
            cleanupFiles(downloadPath);
        }
    }

    private void detectCommercials() {
        Platform.runLater(() -> recording.setStatus(
                ArchiveStatus.createFindingCommercialsStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        String comskipPath = prefs.getComskipPath();
        String comskipIniPath = Paths.get(Paths.get(comskipPath).getParent().toString(), "comskip.ini").toString();
        Path logoPath = buildPath(fixedPath, "logo.txt");
        Path logPath = buildPath(fixedPath, "log");
        ffsplitPath = buildPath(fixedPath, "ffsplit");
        cleanupFiles(logoPath, ffsplitPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(comskipPath);
        cmd.add("--ini");
        cmd.add(comskipIniPath);
        cmd.add("--threads");
        cmd.add(String.valueOf(OSHelper.getProcessorThreads()));
        cmd.add("--ts");
        cmd.add(fixedPath.toString());
        cmd.add(fixedPath.getParent().toString());
        try {
            ComskipOutputReader outputReader = new ComskipOutputReader(recording);
            outputReader.addExitCode(1); // Means that commercials were found
            if (!runProcess(cmd, outputReader)) {
                logger.error("Comskip error: {}", outputReader.getOutput());
                cleanupIntermediateFiles();
                throw new ArchiveTaskException("Error finding commercials");
            } else {
                logger.debug("Comskip output: {}", outputReader.getOutput());
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error running comskip: ", e);
            cleanupIntermediateFiles();
            throw new ArchiveTaskException("Error finding commercials");
        } finally {
            cleanupFiles(logoPath, logPath);
        }
    }

    private void cutCommercials() {
        Platform.runLater(() -> recording.setStatus(
                ArchiveStatus.createRemovingCommercialsStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        double videoOffsetFromAudio = findVideoOffsetStartTime();
        logger.info("Video offset from audio: {}", videoOffsetFromAudio);
        Path partList = buildPath(fixedPath, "parts");
        cleanupFiles(cutPath, partList);
        String ffmpegPath = prefs.getFFmpegPath();
        int filePartCounter = 1;
        List<Path> partPaths = new ArrayList<>();
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(partList))) {
            FFSplitList splitList = FFSplitList.createFromFileWithOffset(ffsplitPath, videoOffsetFromAudio);
            logger.info("splitList: {}", splitList);
            List<FFSplitList.Segment> toKeep = splitList.getSegmentsToKeep();
            int curSegment = 1;
            for (FFSplitList.Segment segment : toKeep) {
                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpegPath);
                cmd.add("-i");
                cmd.add(fixedPath.toString());
                cmd.add("-codec");
                cmd.add("copy");
                cmd.addAll(segment.buildFFmpegOutputParamList());

                Path partPath = buildPath(fixedPath, String.format("part%02d.ts", filePartCounter++));
                logger.debug("partPath = '{}'", partPath);
                writer.println(String.format("file '%s'", partPath.toString().replace("\'", "\'\\\'\'")));
                partPaths.add(partPath);
                cmd.add(partPath.toString());

                try {
                    FFmpegOutputReader outputReader = new FFmpegOutputReader(recording, ArchiveStatus.TaskStatus.NONE);
                    cleanupFiles(partPath);
                    if (!runProcess(cmd, outputReader)) {
                        logger.error("FFmpeg error: {}", outputReader.getOutput());
                        cleanupIntermediateFiles();
                        cleanupFiles(partList);
                        cleanupFiles(partPaths);
                        throw new ArchiveTaskException("Error removing commercials");
                    } else {
                        logger.debug("FFmpeg output: {}", outputReader.getOutput());
                    }
                    double progress = (curSegment++ / (double) toKeep.size()) * 0.9; // The final 10% is for concatenation
                    Platform.runLater(() -> recording.setStatus(
                            ArchiveStatus.createRemovingCommercialsStatus(progress, ArchiveStatus.TIME_UNKNOWN))
                    );
                } catch (InterruptedException | IOException e) {
                    logger.error("Error running ffmpeg to cut commercials: ", e);
                    cleanupIntermediateFiles();
                    cleanupFiles(partList);
                    cleanupFiles(partPaths);
                    throw new ArchiveTaskException("Error removing commercials");
                }
            }
        } catch (IOException e) {
            logger.error("Error reading ffsplit file '{}': ", ffsplitPath, e);
            cleanupFiles(partList);
            cleanupFiles(partPaths);
            return;
        } finally {
            cleanupFiles(fixedPath, ffsplitPath);
        }

        Platform.runLater(() -> recording.setStatus(
                ArchiveStatus.createRemovingCommercialsStatus(.95, 30))
        );

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-f");
        cmd.add("concat");
        cmd.add("-fflags");
        cmd.add("+genpts+discardcorrupt+sortdts");
        cmd.add("-safe");
        cmd.add("0");
        cmd.add("-i");
        cmd.add(partList.toString());
        cmd.add("-codec");
        cmd.add("copy");
        cmd.add("-f");
        cmd.add("mpegts");
        cmd.add(cutPath.toString());
        cleanupFiles(cutPath);
        try {
            FFmpegOutputReader outputReader = new FFmpegOutputReader(recording, ArchiveStatus.TaskStatus.REMOVING_COMMERCIALS);
            if (!runProcess(cmd, outputReader)) {
                logger.error("FFmpeg error: {}", outputReader.getOutput());
                cleanupIntermediateFiles();
                throw new ArchiveTaskException("Error removing commercials");
            } else {
                logger.debug("FFmpeg output: {}", outputReader.getOutput());
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error running ffmpeg to join files: ", e);
            cleanupIntermediateFiles();
            throw new ArchiveTaskException("Error removing commercials");
        } finally {
            cleanupFiles(partList);
            cleanupFiles(partPaths);
        }
    }

    private double findVideoOffsetStartTime() {
        String ffprobePath = prefs.getFFprobePath();
        List<String> cmd = new ArrayList<>();
        cmd.add(ffprobePath);
        cmd.add("-show_streams");
        cmd.add(fixedPath.toString());
        FFprobeOutputReader outputReader = new FFprobeOutputReader(recording);
        try {
            if (!runProcess(cmd, outputReader)) {
                logger.error("FFprobe error: {}", outputReader.getOutput());
                throw new ArchiveTaskException("Error finding video stream start time");
            } else {
                logger.debug("FFprobe output: {}", outputReader.getOutput());
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error running ffprobe: ", e);
            throw new ArchiveTaskException("Error finding video stream start time");
        }
        return outputReader.getVideoOffsetFromAudio();
    }

    private void transcode() {
        Platform.runLater(() -> recording.setStatus(
                ArchiveStatus.createTranscodingStatus(ArchiveStatus.INDETERMINATE, ArchiveStatus.TIME_UNKNOWN))
        );

        VideoResolution videoLimit = prefs.getVideoResolution();
        AudioChannel audioLimit = prefs.getAudioChannels();
        String handbrakePath = prefs.getHandbrakePath();
        Path sourcePath = cutPath;
        if (!Files.exists(sourcePath)) {
            sourcePath = fixedPath;
        }
        boolean useQuickSync = isQuickSyncSupported(handbrakePath, sourcePath) && prefs.getHardwareAcceleration() &&
                recording.getDestinationType().supportsQSV();
        logger.info("Using Intel Quick Sync Video: {}", useQuickSync);
        cleanupFiles(recording.getDestination());
        List<String> cmd = new ArrayList<>();
        cmd.add(handbrakePath);
        cmd.add("-i");
        cmd.add(sourcePath.toString());
        cmd.add("-o");
        cmd.add(recording.getDestination().toString());
        FileType fileType = recording.getDestinationType();
        Map<String, String> handbrakeArgs = new HashMap<>(fileType.getHandbrakeArgs());
        if (audioLimit == AudioChannel.STEREO) {
            logger.info("Audio limit == STEREO");
            // Overwrite the existing list of audio encoders with just one
            handbrakeArgs.put("-E", FileType.getPlatformAudioEncoder());
            handbrakeArgs.put("-a", "1");
            handbrakeArgs.put("-6", "dpl2");
        }
        handbrakeArgs.put("-Y", String.valueOf(videoLimit.getHeight()));
        handbrakeArgs.put("-X", String.valueOf(videoLimit.getWidth()));
        if (useQuickSync) {
            enableQSVEncoder(handbrakeArgs);
        }
        cmd.addAll(mapToList(handbrakeArgs));
        try {
            HandbrakeOutputReader outputReader = new HandbrakeOutputReader(recording);
            if (!runProcess(cmd, outputReader)) {
                logger.error("HandBrake error: {}", outputReader.getOutput());
                cleanupIntermediateFiles();
                cleanupFiles(recording.getDestination());
                throw new ArchiveTaskException("Error compressing video");
            } else {
                logger.debug("HandBrake output: {}", outputReader.getOutput());
            }
        } catch (InterruptedException | IOException e) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setContentText(String.format("Error running '%s': %s\n\nWorking directory: '%s'",
                        cmd.stream().collect(Collectors.joining(" ")), e.getLocalizedMessage(),
                        System.getProperty("user.dir")));
                alert.showAndWait();
            });
            logger.error("Error running HandBrake: ", e);
            cleanupIntermediateFiles();
            cleanupFiles(recording.getDestination());
            throw new ArchiveTaskException("Error compressing video");
        } finally {
            cleanupFiles(cutPath);
        }
    }

    private boolean isQuickSyncSupported(String handbrakePath, Path inputPath) {
        List<String> cmd = new ArrayList<>();
        cmd.add(handbrakePath);
        cmd.add("--scan");
        cmd.add("-i");
        cmd.add(inputPath.toString());
        try {
            HandbrakeScanOutputReader outputReader = new HandbrakeScanOutputReader();
            if (runProcess(cmd, outputReader)) {
                logger.debug("HandBrake output: {}", outputReader.getOutput());
                return outputReader.isQuickSyncSupported();
            } else {
                logger.error("HandBrake error: {}", outputReader.getOutput());
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error checking for QuickSync support: ", e);
        }

        return false;
    }

    /**
     * Replace the default Handbrake encoder arguments with Intel Quick Sync Video encoder arguments.
     */
    private void enableQSVEncoder(Map<String, String> args) {
        args.put("-e", "qsv_h264");
        args.put("--encoder-preset", "balanced");
        args.put("--encoder-level", args.getOrDefault("--h264-level", "4.0"));
        args.put("--encoder-profile", args.getOrDefault("--h264-profile", "main"));
        args.remove("--x264-preset");
        args.remove("--h264-level");
        args.remove("--h264-profile");
    }

    private boolean runProcess(List<String> command, ProcessOutputReader outputReader) throws IOException, InterruptedException {
        if (isCancelled()) {
            return false;
        }

        logger.info("Running command: {}", command.stream().collect(Collectors.joining(" ")));
        ProcessBuilder builder = new ProcessBuilder().command(command).redirectErrorStream(true);
        Process process = builder.start();
        outputReader.setInputStream(process.getInputStream());
        Thread readerThread = new Thread(outputReader);
        readerThread.start();
        while (process.isAlive()) {
            if (isCancelled()) {
                logger.info("Process cancelled, waiting for it to exit");
                readerThread.interrupt();
                process.destroyForcibly();
                process.waitFor();
                readerThread.join();
                logger.info("Process has exited");
                return false;
            } else {
                try {
                    Thread.sleep(200); // check 5 times each second
                } catch (InterruptedException e) {
                    // continue our loop; an interruption likely means isCancelled() will now return true
                }
            }
        }

        int exitCode = process.exitValue();
        if (outputReader.isValidExitCode(exitCode)) {
            return true;
        } else {
            logger.error("Error running command {}: exit code = {}", command, exitCode);
            return false;
        }
    }

    private void cleanupIntermediateFiles() {
        // the List returned from Arrays.asList() doesn't allow elements to be added, so we need copy the elements
        // to a new ArrayList
        List<Path> intermediateFiles = new ArrayList<>(
                Arrays.asList(downloadPath, fixedPath, cutPath, metadataPath, ffsplitPath)
        );
        if (!keepEncryptedFile) {
            intermediateFiles.add(encryptedPath);
        }
        cleanupFiles(intermediateFiles);
    }

    private void cleanupFiles(Path... files) {
        cleanupFiles(Arrays.asList(files));
    }

    private void cleanupFiles(List<Path> files) {
        files.stream().filter(f -> f != null).forEach(f -> {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                logger.error("Error removing {}: ", f, e);
            }
        });
    }

    private Path buildPath(Path input, String newSuffix) {
        String filename = input.getFileName().toString();
        int lastDot = filename.lastIndexOf('.');
        if (lastDot > 0) {
            filename = filename.substring(0, lastDot + 1);
        }
        return Paths.get(input.getParent().toString(), filename + newSuffix);
    }

    private <E> List<E> mapToList(Map<E, E> map) {
        List<E> list = new ArrayList<>();
        for (Map.Entry<E, E> entry : map.entrySet()) {
            list.add(entry.getKey());
            E value = entry.getValue();
            if (value != null) {
                list.add(value);
            }
        }
        return list;
    }
}
