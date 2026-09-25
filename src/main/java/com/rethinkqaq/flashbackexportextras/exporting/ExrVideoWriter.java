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

import com.mojang.blaze3d.platform.NativeImage;
import com.moulberry.flashback.exporting.VideoWriter;
/*? if >=26.1 {*/
/*import com.moulberry.flashback.exporting.ImageFrame;
*//*?}*/
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtras;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig.ExrCompression;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * VideoWriter implementation that writes multi-layer OpenEXR frames
 * (color + depth) instead of a video file.
 */
public class ExrVideoWriter implements VideoWriter {

    private static final int QUEUE_CAPACITY = 8;
    private static final int WRITER_COUNT = 2;
    private static final FramePacket STOP = new FramePacket(-1, null, null, null, false);

    private final MultiLayerExrWriter[] exrWriters;
    private final ArrayBlockingQueue<FramePacket> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final CountDownLatch writerStopped = new CountDownLatch(WRITER_COUNT);
    private final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
    private final Thread[] writerThreads = new Thread[WRITER_COUNT];
    private final Deque<NativeImage> pendingColors = new ArrayDeque<>();
    private final boolean sceneLinearHdr;
    private final boolean sLog3Encoding;
    private volatile boolean accepting = true;
    private boolean finished;
    private long nextFrameId;
    private long encodedFrameCount;

    public ExrVideoWriter(Path outputDir, int width, int height, boolean sceneLinearHdr,
                          boolean sLog3Encoding, boolean acesCctEncoding,
                          ExrCompression compression) throws IOException {
        this.sceneLinearHdr = sceneLinearHdr;
        this.sLog3Encoding = sLog3Encoding;
        this.exrWriters = new MultiLayerExrWriter[WRITER_COUNT];
        try {
            for (int i = 0; i < WRITER_COUNT; i++) {
                exrWriters[i] = new MultiLayerExrWriter(outputDir, width, height,
                        FlashbackExportExtrasConfig.INSTANCE.depthLinearizeWorldSpace, sceneLinearHdr,
                        sLog3Encoding, acesCctEncoding, compression);
            }
        } catch (IOException | RuntimeException e) {
            for (MultiLayerExrWriter writer : exrWriters) {
                if (writer != null) writer.close();
            }
            throw e;
        }
        for (int i = 0; i < WRITER_COUNT; i++) {
            final int workerIndex = i;
            Thread writerThread = new Thread(() -> writeLoop(workerIndex),
                    "flashbackexportextras-exr-writer-" + i);
            writerThread.setDaemon(true);
            writerThread.start();
            writerThreads[i] = writerThread;
        }
        FlashbackExportExtras.LOGGER.info("EXR writers started: output={}, workers={}, queueCapacity={}, sceneLinearHdr={}, slog3={}, acesCct={}, compression={}",
                outputDir, WRITER_COUNT, QUEUE_CAPACITY, sceneLinearHdr, sLog3Encoding, acesCctEncoding, compression);
    }

    /*? if >=26.1 {*/
    /*@Override
    public void encode(ImageFrame frame) {
        NativeImage colorImage = frame.toOpaqueRgbaU8NativeImage();
        try {
            encodeNative(colorImage, frame.audioBuffer);
        } finally {
            frame.close();
        }
    }
    *//*?}*/

    /*? if <26.1 {*/
    @Override
    public void encode(NativeImage colorImage, FloatBuffer audioBuffer) {
        encodeNative(colorImage, audioBuffer);
    }
    //?}

    private void encodeNative(NativeImage colorImage, FloatBuffer audioBuffer) {
        if (!accepting) {
            colorImage.close();
            throw new IllegalStateException("EXR writer is already finished");
        }
        Throwable failure = writerFailure.get();
        if (failure != null) {
            colorImage.close();
            throw new IllegalStateException("EXR writer has failed", failure);
        }

        pendingColors.addLast(colorImage);
        drainPairs();
    }

    private void drainPairs() {
        while (!pendingColors.isEmpty()) {
            DepthCaptureState.DepthFrame depthFrame;
            // Scene-linear half-float or GPU-encoded S-Log3 RGBA16 color data.
            ByteBuffer hdrData = null;
            boolean hdrFromVideoQueue = false;
            depthFrame = DepthCaptureState.peek(nextFrameId);
            if (depthFrame == null) break;

            if (sceneLinearHdr) {
                SceneLinearHdrCaptureState.ColorFrame hdrFrame = SceneLinearHdrCaptureState.poll(nextFrameId);
                if (hdrFrame == null) break;
                hdrData = hdrFrame.data;
            }
            //? if hdr {
            else if (sLog3Encoding) {
                // S-Log3 frames come from the HDR video capture queue; the
                // GPU already applied decode + BT.2020 + S-Log3.
                HdrVideoCaptureState.Frame slog3Frame = HdrVideoCaptureState.poll(nextFrameId);
                if (slog3Frame == null) break;
                hdrData = slog3Frame.data;
                hdrFromVideoQueue = true;
            }
            //?}

            depthFrame = DepthCaptureState.poll(nextFrameId);

            NativeImage colorImage = pendingColors.removeFirst();
            FramePacket packet = new FramePacket((int) nextFrameId++, colorImage, depthFrame,
                    hdrData, hdrFromVideoQueue);
            try {
                if (!queue.offer(packet, 30, TimeUnit.SECONDS)) {
                    packet.close();
                    throw new IllegalStateException("Timed out waiting for EXR writer queue");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                packet.close();
                throw new IllegalStateException("Interrupted while waiting for EXR writer", e);
            }
            encodedFrameCount++;
        }
    }

    private void discardPendingColors() {
        NativeImage color;
        while ((color = pendingColors.pollFirst()) != null) {
            color.close();
        }
    }

    private void writeLoop(int workerIndex) {
        try {
            while (true) {
                FramePacket packet = queue.take();
                if (packet == STOP) return;
                try {
                    if (packet.hdrColor != null) {
                        exrWriters[workerIndex].writeHdrFrame(packet.hdrColor, packet.depth,
                                packet.frameId);
                    } else {
                        exrWriters[workerIndex].writeFrame(packet.color, packet.depth, packet.frameId);
                    }
                } catch (Throwable t) {
                    writerFailure.compareAndSet(null, t);
                    return;
                } finally {
                    packet.close();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerFailure.compareAndSet(null, e);
        } finally {
            // Do not drain the shared queue here. Another worker may still
            // need its STOP sentinel; draining it here can make that worker
            // block forever in queue.take(). Remaining packets are cleaned
            // after all workers have stopped in finishInternal().
            writerStopped.countDown();
        }
    }

    private void finishInternal() {
        if (finished) return;
        finished = true;
        accepting = false;
        FlashbackExportExtras.LOGGER.info("EXR finish: draining pending pairs");
        try {
            if (sceneLinearHdr) SceneLinearHdrCaptureState.throwIfFailed();
            /*? if hdr {*/
            if (sLog3Encoding) HdrVideoCaptureState.throwIfFailed();
            /*?}*/
            drainPairs();
            if (!pendingColors.isEmpty()) {
                writerFailure.compareAndSet(null, new IllegalStateException(
                        "Missing matching " + (sceneLinearHdr || sLog3Encoding ? "HDR color or " : "")
                                + "depth data for EXR frame " + nextFrameId
                                + " (pendingColors=" + pendingColors.size()
                                + ", depthQueued=" + DepthCaptureState.queuedFrameCount()
                                + ", sceneLinearQueued=" + SceneLinearHdrCaptureState.size() + ")"));
            }
            int remainingDepth = DepthCaptureState.queuedFrameCount();
            int remainingHdr = SceneLinearHdrCaptureState.size();
            if (remainingDepth != 0 || (sceneLinearHdr && remainingHdr != 0)) {
                writerFailure.compareAndSet(null, new IllegalStateException(
                        "Unmatched EXR data after frame " + nextFrameId
                                + ": depth=" + remainingDepth + ", hdr=" + remainingHdr));
            }
            if (sceneLinearHdr) {
                SceneLinearHdrCaptureState.verifyComplete(nextFrameId, nextFrameId);
            }
            /*? if hdr {*/
            if (sLog3Encoding) {
                HdrVideoCaptureState.verifyComplete(nextFrameId, nextFrameId);
            }
            /*?}*/
            discardPendingColors();
            FlashbackExportExtras.LOGGER.info("EXR finish: queue={}, sending {} stop signals",
                    queue.size(), WRITER_COUNT);
            // Every worker has its own blocking take(); one sentinel is not
            // enough to release all workers during finalization.
            for (int i = 0; i < WRITER_COUNT; i++) {
                if (!queue.offer(STOP, 30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out queueing EXR writer stop signal");
                }
            }
            FlashbackExportExtras.LOGGER.info("EXR finish: waiting for {} writer threads", WRITER_COUNT);
            if (!writerStopped.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for EXR writer threads");
            }
            FlashbackExportExtras.LOGGER.info("EXR finish: writer threads stopped");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writerFailure.compareAndSet(null, e);
            for (Thread thread : writerThreads) {
                if (thread != null) thread.interrupt();
            }
        } catch (RuntimeException e) {
            writerFailure.compareAndSet(null, e);
            for (Thread thread : writerThreads) {
                if (thread != null) thread.interrupt();
            }
        } finally {
            discardPendingColors();
            FramePacket packet;
            while ((packet = queue.poll()) != null) {
                if (packet != STOP) packet.close();
            }
            FlashbackExportExtras.LOGGER.info("EXR finish: closing native writers");
            for (MultiLayerExrWriter writer : exrWriters) {
                if (writer != null) writer.close();
            }
            FlashbackExportExtras.LOGGER.info("EXR finish: native writers closed");
        }

        Throwable failure = writerFailure.get();
        if (failure != null) {
            FlashbackExportExtras.LOGGER.error("EXR writer failed", failure);
            throw new IllegalStateException("OpenEXR export failed", failure);
        }
        FlashbackExportExtras.LOGGER.info("EXR writer finished: encoded={}, pendingColors={}, pendingDepth={}",
                encodedFrameCount, pendingColors.size(), DepthCaptureState.queuedFrameCount());
    }

    @Override
    /*? if >=1.21.5 {*/
    /*public void finish(Consumer<String> statusConsumer) {
    *//*?} else {*/
    public void finish() {
    /*?}*/
        // EXR uses the main Flashback export progress. Do not emit a second
        // progress phase for the camera path or the EXR writer queue.
        finishInternal();
    }

    @Override
    public void close() {
        finishInternal();
    }

    private static final class FramePacket {
        private final int frameId;
        private final NativeImage color;
        private final DepthCaptureState.DepthFrame depth;
        /** Scene-linear RGBA16F or GPU-encoded S-Log3 RGBA16 color data. */
        private final ByteBuffer hdrColor;
        /** True when hdrColor came from HdrVideoCaptureState (freed via MemoryUtil). */
        private final boolean hdrFromVideoQueue;

        private FramePacket(int frameId, NativeImage color, DepthCaptureState.DepthFrame depth,
                            ByteBuffer hdrColor, boolean hdrFromVideoQueue) {
            this.frameId = frameId;
            this.color = color;
            this.depth = depth;
            this.hdrColor = hdrColor;
            this.hdrFromVideoQueue = hdrFromVideoQueue;
        }

        private void close() {
            if (color != null) color.close();
            if (depth != null) DepthCaptureState.releaseBuffer(depth.data);
            if (hdrColor != null) {
                if (hdrFromVideoQueue) {
                    MemoryUtil.memFree(hdrColor);
                } else {
                    SceneLinearHdrCaptureState.release(hdrColor);
                }
            }
        }
    }
}
