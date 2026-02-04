package com.nptechon.smartamp.broadcast.controller;

import com.nptechon.smartamp.global.config.UploadProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/voice")
public class VoiceController {

    private final UploadProperties uploadProperties;

    /**
     * EC2 프리티어 안정 운영용: 동시 변환 1개로 제한
     */
    private static final int MAX_CONCURRENT_CONVERSIONS = 1;
    private static final Semaphore CONVERT_SEMAPHORE =
            new Semaphore(MAX_CONCURRENT_CONVERSIONS, true);
    private static final long ACQUIRE_TIMEOUT_SECONDS = 10;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("ampId") String ampId
    ) throws IOException {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "file is empty"
            ));
        }

        boolean acquired;
        try {
            acquired = CONVERT_SEMAPHORE.tryAcquire(
                    ACQUIRE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "interrupted while waiting for conversion slot"
            ));
        }

        if (!acquired) {
            return ResponseEntity.status(429).body(Map.of(
                    "success", false,
                    "message", "server is busy converting audio, try again later",
                    "maxConcurrent", MAX_CONCURRENT_CONVERSIONS
            ));
        }

        Path dir = Paths.get(uploadProperties.getDir());
        Files.createDirectories(dir);

        String mp3Name = System.currentTimeMillis() + "_voice.mp3";
        Path targetMp3 = dir.resolve(mp3Name);

        long startMs = System.currentTimeMillis();

        try (InputStream wavStream = file.getInputStream()) {

            log.info(
                    "convert start: ampId={}, origName={}, size={}, availablePermits={}",
                    ampId,
                    file.getOriginalFilename(),
                    file.getSize(),
                    CONVERT_SEMAPHORE.availablePermits()
            );

            convertToMp3AndSave(wavStream, targetMp3);

            long mp3Size = Files.size(targetMp3);
            long tookMs = System.currentTimeMillis() - startMs;

            log.info(
                    "convert done: ampId={}, savedAs={}, mp3Size={}, tookMs={}",
                    ampId,
                    mp3Name,
                    mp3Size,
                    tookMs
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "ampId", ampId,
                    "savedAs", mp3Name,
                    "size", mp3Size,
                    "tookMs", tookMs
            ));

        } catch (Exception e) {
            log.error("convert failed: ampId={}", ampId, e);
            try {
                Files.deleteIfExists(targetMp3);
            } catch (Exception ignore) {
            }
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "convert failed",
                    "detail", e.getMessage()
            ));
        } finally {
            CONVERT_SEMAPHORE.release();
            log.debug(
                    "permit released: availablePermits={}",
                    CONVERT_SEMAPHORE.availablePermits()
            );
        }
    }

    /**
     * MultipartFile InputStream(wav)을 ffmpeg stdin 으로 넣고,
     * ffmpeg 가 만든 mp3를 파일로 저장
     *
     * EC2 프리티어 최적화
     * - 모노: -ac 1
     * - 낮은 비트레이트: 96k (필요하면 64k)
     */
    private void convertToMp3AndSave(
            InputStream wavStream,
            Path outputMp3
    ) throws IOException, InterruptedException {

        // RN에서 wav로 보내는 전제
        List<String> cmd = List.of(
                uploadProperties.getFfmpegPath(),
                "-y",
                "-hide_banner",
                "-loglevel", "error",
                "-f", "wav",
                "-i", "pipe:0",
                "-vn",
                "-ac", "1",          // mono
                "-b:a", "96k",       // 64k~96k 권장
                "-codec:a", "libmp3lame",
                outputMp3.toAbsolutePath().toString()
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();

        // stderr 수집
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errThread = new Thread(() -> {
            try (InputStream es = p.getErrorStream()) {
                es.transferTo(errBuf);
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stderr-reader");
        errThread.start();

        // wav -> ffmpeg stdin
        try (OutputStream ffmpegIn = p.getOutputStream()) {
            wavStream.transferTo(ffmpegIn);
        }

        // 변환 완료 대기
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("ffmpeg timeout");
        }

        errThread.join(2000);

        int exit = p.exitValue();
        if (exit != 0) {
            String errText = errBuf.toString(StandardCharsets.UTF_8);
            throw new IOException(
                    "ffmpeg failed (exit=" + exit + "): " + errText
            );
        }

        if (!Files.exists(outputMp3) || Files.size(outputMp3) == 0) {
            throw new IOException("mp3 output not created or empty");
        }
    }
}
