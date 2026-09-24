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
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * HDR VideoWriter for HDR10 export.
 *
 * Streams 16-bit RGBA frames (PQ-encoded, BT.2020 primaries) to FFmpeg
 * via stdin pipe. Frames are written immediately on addHdrFrame() and
 * freed right after — no accumulation in memory.
 */
public class HdrVideoWriter implements VideoWriter {

    private final Path outputPath;
    private final int width;
    private final int height;
    private final double framerate;
    private final int bitrate;
    private final int frameSize;
    private final byte[] frameBytes;  // reusable write buffer
    private Process ffmpegProcess;
    private OutputStream ffmpegStdin;
    private int frameCount;
    private boolean finished;
    private boolean pipeFailed;

    public HdrVideoWriter(Path outputPath, int width, int height, double framerate, int bitrate) throws IOException {
        this.outputPath = outputPath;
        this.width = width;
        this.height = height;
        this.framerate = framerate;
        this.bitrate = bitrate;
        this.frameSize = width * height * 8;
        this.frameBytes = new byte[frameSize];
        Files.createDirectories(outputPath.getParent());
        FlashbackExportExtras.LOGGER.info("HDR encoder requested bitrate: {} bps ({} Mbps)",
                bitrate, bitrate / 1_000_000.0);
        FlashbackExportExtras.LOGGER.info("HDR video export: {}x{} @ {}fps → {}",
                width, height, framerate, outputPath);
    }

    private void ensureStarted() throws IOException {
        if (ffmpegProcess != null) return;
        String outputStr = outputPath.toAbsolutePath().toString();
        ProcessBuilder pb = new ProcessBuilder(
            "ffmpeg",
            "-y",
            "-f", "rawvideo",
            "-pixel_format", "rgba64",
            "-video_size", width + "x" + height,
            "-framerate", String.valueOf((int) framerate),
            "-i", "pipe:0",
            "-c:v", "libx265",
            "-preset", "medium",
            "-b:v", String.valueOf(bitrate),
            "-minrate", String.valueOf(bitrate),
            "-maxrate", String.valueOf(bitrate),
            "-bufsize", String.valueOf(bitrate),
            "-pix_fmt", "yuv420p10le",
            "-color_primaries", "bt2020",
            "-color_trc", "smpte2084",
            "-colorspace", "bt2020nc",
            "-color_range", "pc",
            "-x265-params", "hdr-opt=1:repeat-headers=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc"
                    + ":nal-hrd=cbr:vbv-maxrate=" + Math.max(1, bitrate / 1000)
                    + ":vbv-bufsize=" + Math.max(1, bitrate / 1000)
                    + ":filler=1",
            outputStr
        );
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        ffmpegProcess = pb.start();
        ffmpegStdin = ffmpegProcess.getOutputStream();
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
        FlashbackExportExtras.LOGGER.info("HDR export: {} frames → {}", frameCount, outputPath);
    }

    @Override
    public void close() {
        finished = true;
        if (ffmpegProcess != null) ffmpegProcess.destroy();
    }
}
//?}
