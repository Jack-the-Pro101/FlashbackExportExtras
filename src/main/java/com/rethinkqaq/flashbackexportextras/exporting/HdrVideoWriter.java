/*
 * Flashback Export Extras
 * Copyright (C) RethinkQAQ
 *
 * This file is part of Flashback Export Extras.
 *
 * Flashback Export Extras is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Flashback Export Extras is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along
 * with Flashback Export Extras. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package com.rethinkqaq.flashbackexportextras.exporting;

//? if hdr {

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.VideoWriter;
/*? if >=26.1 {*/
/*import com.moulberry.flashback.exporting.ImageFrame;
*//*?}*/
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtras;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * HDR VideoWriter for HDR10 export.
 *
 * Streams 16-bit RGBA frames (PQ-encoded, BT.2020 primaries) to FFmpeg
 * via stdin pipe. Frames are written immediately on addHdrFrame() and
 * freed right after — no accumulation in memory.
 */
public class HdrVideoWriter implements VideoWriter {

    /** ST.2084 PQ transfer function (HDR10). */
    public static final int TRANSFER_PQ = 11;
    /** Sony S-Log3 transfer function. */
    public static final int TRANSFER_S_LOG3 = 12;

    /**
     * Resolves the ffmpeg video encoder to use for an HDR export, or null when
     * the requested codec/encoder combination cannot carry 10-bit HDR color
     * (the caller should fall back to Flashback's normal SDR writer).
     *
     * Supported: software H.264/H.265/AV1/VP9 (10-bit yuv420p10le), Apple
     * ProRes (yuv444p10le, 4444 profile), and NVENC/AMF/VideoToolbox hardware
     * H.264/HEVC. QSV/VAAPI need device setup and image codecs (GIF/WebP/PNG/
     * EXR/qtrle) cannot hold HDR — those return null.
     */
    public static String resolveVideoEncoder(String codecName, String encoderName) {
        String encoder = encoderName == null || encoderName.isBlank()
                ? defaultEncoderFor(codecName) : encoderName;
        if (encoder == null) return null;
        String lower = encoder.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("libx265") || lower.equals("libx264")
                || lower.startsWith("hevc_nvenc") || lower.startsWith("h264_nvenc")
                || lower.startsWith("hevc_amf") || lower.startsWith("h264_amf")
                || lower.contains("videotoolbox")
                || lower.equals("libaom-av1") || lower.equals("libsvtav1") || lower.startsWith("av1_nvenc")
                || lower.equals("libvpx-vp9")) {
            return encoder;
        }
        if (lower.equals("prores_ks") || lower.equals("prores_aw")) {
            return encoder;
        }
        // qsv/vaapi (need device setup), qtrle, gif, webp, png, unknown → not supported.
        return null;
    }

    private static String defaultEncoderFor(String codecName) {
        if (codecName == null) return null;
        return switch (codecName.toUpperCase(java.util.Locale.ROOT)) {
            case "H265" -> "libx265";
            case "H264" -> "libx264";
            case "AV1" -> "libsvtav1";
            case "VP9" -> "libvpx-vp9";
            case "PRO_RES", "QUICK_TIME" -> "prores_ks";
            default -> null;
        };
    }

    private static boolean isProResEncoder(String encoder) {
        return encoder != null && encoder.toLowerCase(java.util.Locale.ROOT).startsWith("prores");
    }

    private final Path outputPath;
    private final Path videoTempPath;
    private final Path audioTempPath;
    private final int width;
    private final int height;
    private final double framerate;
    private final int bitrate;
    private final int transferFunction;
    private final int audioChannels;
    private final String audioEncoder;
    private final String videoEncoder;
    private final int frameSize;
    private final byte[] frameBytes;  // reusable write buffer
    private Process ffmpegProcess;
    private OutputStream ffmpegStdin;
    private OutputStream audioOut;
    private ByteBuffer audioScratch;
    private long audioSampleFrames;
    private int frameCount;
    private boolean finished;
    private boolean pipeFailed;

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate) throws IOException {
        this(outputPath, width, height, framerate, bitrate, TRANSFER_PQ, 0, "aac", "H265", "libx265");
    }

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate,
                          int transferFunction) throws IOException {
        this(outputPath, width, height, framerate, bitrate, transferFunction, 0, "aac", "H265", "libx265");
    }

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate,
                          int transferFunction, int audioChannels, String audioCodec) throws IOException {
        this(outputPath, width, height, framerate, bitrate, transferFunction, audioChannels, audioCodec,
                "H265", "libx265");
    }

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate,
                          int transferFunction, int audioChannels, String audioCodec,
                          String videoEncoder) throws IOException {
        this(outputPath, width, height, framerate, bitrate, transferFunction, audioChannels, audioCodec,
                null, videoEncoder);
    }

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate,
                          int transferFunction, int audioChannels, String audioCodec,
                          String videoCodecName, String videoEncoder) throws IOException {
        this.outputPath = outputPath;
        this.videoTempPath = tempSibling(outputPath, ".video.tmp");
        this.audioTempPath = tempSibling(outputPath, ".audio.f32");
        this.width = width;
        this.height = height;
        this.framerate = framerate;
        this.bitrate = bitrate;
        this.transferFunction = transferFunction == TRANSFER_S_LOG3 ? TRANSFER_S_LOG3 : TRANSFER_PQ;
        this.audioChannels = Math.max(0, audioChannels);
        this.audioEncoder = mapAudioCodec(audioCodec);
        this.videoEncoder = resolveVideoEncoder(videoCodecName, videoEncoder);
        if (this.videoEncoder == null) {
            throw new IllegalArgumentException("Unsupported HDR video encoder: " + videoEncoder);
        }
        this.frameSize = width * height * 8;
        this.frameBytes = new byte[frameSize];
        Files.createDirectories(outputPath.getParent());
        FlashbackExportExtras.LOGGER.info("HDR encoder requested bitrate: {} bps ({} Mbps)",
                bitrate, bitrate / 1_000_000.0);
        FlashbackExportExtras.LOGGER.info("{} video export: {}x{} @ {}fps, {} encoder, audio={}ch/{} → {}",
                this.transferFunction == TRANSFER_S_LOG3 ? "S-Log3" : "HDR10",
                width, height, framerate, this.videoEncoder, this.audioChannels, this.audioEncoder, outputPath);
    }

    /** Maps Flashback's AudioCodec names to ffmpeg encoder names. */
    private static String mapAudioCodec(String codec) {
        if (codec == null) return "aac";
        return switch (codec.toUpperCase(java.util.Locale.ROOT)) {
            case "MP3" -> "libmp3lame";
            case "OPUS" -> "libopus";
            case "VORBIS" -> "libvorbis";
            default -> "aac";
        };
    }

    /**
     * Sibling temp path for the given output, inserting the suffix before the
     * container extension ("x.mp4" → "x.video.tmp.mp4") so ffmpeg can still
     * infer the muxer from the file name; falls back to appending when the
     * output has no extension.
     */
    private static Path tempSibling(Path output, String suffix) {
        String name = output.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return output.resolveSibling(base + suffix + extension);
    }

    private void ensureStarted() throws IOException {
        if (ffmpegProcess != null) return;
        // The video-only encode writes to an intermediate file; audio is
        // muxed in a second pass at finish() (Java cannot open extra pipe
        // fds for a combined single-pass encode).
        String outputStr = videoTempPath.toAbsolutePath().toString();
        boolean slog3 = transferFunction == TRANSFER_S_LOG3;
        boolean prores = isProResEncoder(videoEncoder);
        String lowerEncoder = videoEncoder.toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add("ffmpeg");
        args.add("-y");
        // Input-side color properties travel with the frames, which is how
        // ffmpeg reliably propagates them into the output stream (required
        // for the MOV/MP4 'colr' atom; output-side flags alone are dropped
        // by some encoders such as ProRes).
        args.add("-color_primaries");
        args.add("bt2020");
        args.add("-colorspace");
        args.add("bt2020nc");
        if (!slog3) {
            args.add("-color_trc");
            args.add("smpte2084");
        }
        args.add("-f");
        args.add("rawvideo");
        args.add("-pixel_format");
        args.add("rgba64");
        args.add("-video_size");
        args.add(width + "x" + height);
        args.add("-framerate");
        args.add(String.valueOf((int) framerate));
        args.add("-i");
        args.add("pipe:0");
        args.add("-c:v");
        args.add(videoEncoder);
        if (prores) {
            // ProRes 4444: 10-bit 4:4:4. The encoder is rate-controlled via
            // -b:v (mapped to bits_per_mb); no CBR/HRD/VUI x-params apply.
            if (lowerEncoder.equals("prores_ks")) {
                args.add("-profile:v");
                args.add("4444");
            }
            args.add("-vendor");
            args.add("apl0");
            args.add("-pix_fmt");
            args.add("yuv444p10le");
            args.add("-b:v");
            args.add(String.valueOf(bitrate));
        } else {
            if (lowerEncoder.equals("libx265") || lowerEncoder.equals("libx264")) {
                args.add("-preset");
                args.add("medium");
            }
            args.add("-b:v");
            args.add(String.valueOf(bitrate));
            args.add("-minrate");
            args.add(String.valueOf(bitrate));
            args.add("-maxrate");
            args.add(String.valueOf(bitrate));
            args.add("-bufsize");
            args.add(String.valueOf(bitrate));
            args.add("-pix_fmt");
            args.add("yuv420p10le");
        }
        args.add("-color_primaries");
        args.add("bt2020");
        args.add("-colorspace");
        args.add("bt2020nc");
        args.add("-color_range");
        args.add("pc");
        if (!slog3) {
            args.add("-color_trc");
            args.add("smpte2084");
        }
        // Note: H.265 VUI (H.273) has no S-Log3 transfer characteristic code
        // point, so S-Log3 output leaves the transfer characteristic
        // unspecified (same as Sony's own XAVC S-Log3 files). The pixel data
        // is S-Log3 encoded; NLEs select "S-Log3" manually.
        if (lowerEncoder.equals("libx265")) {
            args.add("-x265-params");
            args.add("repeat-headers=1:colorprim=bt2020:colormatrix=bt2020nc"
                    + (slog3 ? "" : ":hdr-opt=1:transfer=smpte2084")
                    + ":nal-hrd=cbr:vbv-maxrate=" + Math.max(1, bitrate / 1000)
                    + ":vbv-bufsize=" + Math.max(1, bitrate / 1000)
                    + ":filler=1");
        } else if (lowerEncoder.equals("libx264")) {
            args.add("-x264-params");
            args.add("colorprim=bt2020:colormatrix=bt2020nc"
                    + (slog3 ? "" : ":transfer=smpte2084"));
        }
        // MOV/MP4 need an explicit request to write the 'colr' color
        // description atom; MKV/WebM tag color natively from stream props.
        String extension = videoTempPath.getFileName().toString();
        int dot = extension.lastIndexOf('.');
        String container = dot >= 0 ? extension.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        if (container.equals("mp4") || container.equals("mov")) {
            args.add("-movflags");
            args.add("+write_colr");
        }
        args.add(outputStr);
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        ffmpegProcess = pb.start();
        ffmpegStdin = ffmpegProcess.getOutputStream();
    }

    /**
     * Accumulates interleaved float32 PCM samples (Flashback's per-frame
     * audioBuffer, 48000 Hz) into a temp file for muxing at finish(). Java
     * cannot open additional ffmpeg pipe fds, so the raw audio is staged on
     * disk and muxed in a second, stream-copy pass.
     */
    public void addAudioFrame(FloatBuffer audio) {
        if (audio == null || audioChannels == 0 || finished || pipeFailed) return;
        try {
            if (audioOut == null) {
                audioOut = Files.newOutputStream(audioTempPath);
            }
            int floats = audio.remaining();
            if (floats <= 0) return;
            if (audioScratch == null || audioScratch.capacity() < floats * 4) {
                audioScratch = ByteBuffer.allocate(Math.max(floats * 4, 65536))
                        .order(ByteOrder.LITTLE_ENDIAN);
            }
            audioScratch.clear();
            audioScratch.asFloatBuffer().put(audio);
            audioOut.write(audioScratch.array(), 0, floats * 4);
            audioSampleFrames += floats / audioChannels;
        } catch (IOException e) {
            pipeFailed = true;
            FlashbackExportExtras.LOGGER.error("HDR export: audio write failed", e);
            closeQuietly(audioOut);
            audioOut = null;
        }
    }

    /**
     * Writes a 16-bit RGBA frame directly to FFmpeg stdin, then frees it.
     */
    public void addHdrFrame(long frameId, ByteBuffer hdrData) {
        if (finished || pipeFailed) {
            MemoryUtil.memFree(hdrData);
            return;
        }
        if (frameId != frameCount) {
            MemoryUtil.memFree(hdrData);
            throw new IllegalStateException("HDR10 frame mismatch: expected "
                    + frameCount + ", received " + frameId);
        }
        boolean bufferOwned = true;
        try {
            ensureStarted();
            // Copy to reusable buffer and free immediately
            hdrData.rewind();
            hdrData.get(frameBytes);
            MemoryUtil.memFree(hdrData);
            bufferOwned = false;
            ffmpegStdin.write(frameBytes);
            frameCount++;
        } catch (IOException e) {
            pipeFailed = true;
            FlashbackExportExtras.LOGGER.error("HDR export: pipe write failed at frame {}", frameCount, e);
            if (bufferOwned) MemoryUtil.memFree(hdrData);
            if (ffmpegProcess != null) ffmpegProcess.destroy();
        }
    }

    public int getFrameCount() {
        return frameCount;
    }

    /*? if >=26.1 {*/
    /*@Override
    public void encode(ImageFrame frame) {
        // HDR frames arrive through addHdrFrame(); release the normal SDR
        // frame that Flashback hands to the writer.
        frame.close();
    }
    *//*?}*/

    /*? if <26.1 {*/
    @Override
    public void encode(NativeImage image, FloatBuffer audioBuffer) {
        // HDR frames arrive through addHdrFrame(). The redirect normally owns
        // and closes this image, but keep the legacy interface implementation.
        image.close();
    }
    //?}

    @Override
    /*? if >=1.21.5 {*/
    /*public void finish(Consumer<String> statusConsumer) {
    *//*?} else {*/
    public void finish() {
    /*?}*/
        if (finished) return;
        finished = true;
        if (pipeFailed) {
            if (ffmpegProcess != null) ffmpegProcess.destroy();
            FlashbackExportExtras.LOGGER.warn("HDR export aborted after FFmpeg pipe failure at frame {}", frameCount);
            return;
        }
        if (ffmpegStdin != null) {
            try { ffmpegStdin.flush(); ffmpegStdin.close(); } catch (IOException ignored) {}
        }
        if (ffmpegProcess != null) {
            try {
                int exitCode = ffmpegProcess.waitFor();
                if (exitCode != 0) {
                    FlashbackExportExtras.LOGGER.warn("FFmpeg exited with code {}", exitCode);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ffmpegProcess.destroy();
            }
        }
        FlashbackExportExtras.LOGGER.info("HDR export: {} frames, {} audio sample frames → {}",
                frameCount, audioSampleFrames, outputPath);
        if (frameCount == 0) {
            cleanupTempFiles();
            // Fail loudly instead of silently leaving an empty output file.
            // A zero-frame result means no GPU readback ever succeeded — the
            // usual cause is a color transform shader compile failure, which
            // is reported separately in the game log.
            throw new IllegalStateException(
                    "HDR export produced no frames; the HDR color transform pipeline failed (see game log)");
        }
        if (!muxAudio()) {
            FlashbackExportExtras.LOGGER.warn("HDR export: audio muxing skipped, video-only output kept");
        }
    }

    /**
     * Muxes the staged raw PCM audio into the encoded video with a stream
     * copy. Falls back to the video-only file if audio was not recorded or
     * the mux pass fails.
     */
    private boolean muxAudio() {
        if (audioChannels == 0 || audioSampleFrames == 0) {
            moveVideoTempToOutput();
            return true;
        }
        closeQuietly(audioOut);
        audioOut = null;
        if (!Files.exists(videoTempPath)) {
            FlashbackExportExtras.LOGGER.error("HDR export: encoded video temp file is missing");
            return false;
        }
        if (Files.exists(outputPath)) {
            try {
                Files.delete(outputPath);
            } catch (IOException e) {
                FlashbackExportExtras.LOGGER.error("HDR export: failed to replace stale output", e);
                return false;
            }
        }
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", videoTempPath.toAbsolutePath().toString(),
                "-f", "f32le",
                "-ar", "48000",
                "-ac", String.valueOf(audioChannels),
                "-i", audioTempPath.toAbsolutePath().toString(),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
                "-c:a", audioEncoder,
                "-b:a", "256k",
                outputPath.toAbsolutePath().toString());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                FlashbackExportExtras.LOGGER.error("HDR export: audio mux pass failed with code {}", exitCode);
                return false;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            FlashbackExportExtras.LOGGER.error("HDR export: audio mux pass failed", e);
            return false;
        } finally {
            cleanupTempFiles();
        }
        return Files.exists(outputPath);
    }

    private void moveVideoTempToOutput() {
        if (!Files.exists(videoTempPath)) return;
        try {
            if (Files.exists(outputPath)) Files.delete(outputPath);
            try {
                Files.move(videoTempPath, outputPath);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(videoTempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            FlashbackExportExtras.LOGGER.error("HDR export: failed to finalize video file", e);
        }
    }

    private void cleanupTempFiles() {
        closeQuietly(audioOut);
        audioOut = null;
        try {
            Files.deleteIfExists(videoTempPath);
            Files.deleteIfExists(audioTempPath);
        } catch (IOException e) {
            FlashbackExportExtras.LOGGER.warn("HDR export: failed to remove temp files", e);
        }
    }

    private static void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.flush();
        } catch (IOException ignored) {}
        try {
            stream.close();
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        finished = true;
        if (ffmpegProcess != null) ffmpegProcess.destroy();
        closeQuietly(audioOut);
        audioOut = null;
        cleanupTempFiles();
    }
}
//?}
