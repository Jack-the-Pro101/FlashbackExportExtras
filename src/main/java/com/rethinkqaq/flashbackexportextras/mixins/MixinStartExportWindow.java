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
package com.rethinkqaq.flashbackexportextras.mixins;

import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.combo_options.VideoCodec;
import com.moulberry.flashback.state.EditorState;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig;
import com.rethinkqaq.flashbackexportextras.FlashbackExportExtrasConfig.ExportMode;
import com.rethinkqaq.flashbackexportextras.exporting.CameraPathExporter;
import com.rethinkqaq.flashbackexportextras.exporting.HdrExportState;
//? if hdr {
import com.rethinkqaq.flashbackexportextras.exporting.HdrVideoWriter;
//?}
import com.rethinkqaq.flashbackexportextras.gpu.GpuExportBackendFactory;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImString;
import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.moulberry.flashback.exporting.ExportSettings;

/**
 * GUI additions:
 * - Format selector: Video / OpenEXR Sequence (Depth)
 * - Camera path export checkbox + relative origin sub-option
 */
@Mixin(value = com.moulberry.flashback.editor.ui.windows.StartExportWindow.class, remap = false)
public class MixinStartExportWindow {

    private static final ImString EXR_OUTPUT_NAME = new ImString("", 128);
    private static boolean exrOutputNameInitialized;
    private static final DateTimeFormatter EXR_DEFAULT_NAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'_HH_mm");

    /** Refresh the automatic EXR directory name whenever Flashback opens this window. */
    @Inject(method = "open", at = @At("HEAD"), remap = false)
    private static void flashbackexportextras$resetOutputNameOnOpen(CallbackInfo ci) {
        exrOutputNameInitialized = false;
    }

    /** Trace the asynchronous folder/file selection before an ExportJob exists. */
    @Inject(method = "createExportSettings", at = @At("RETURN"), remap = false)
    private static void flashbackexportextras$traceExportSettings(String jobName, FlashbackConfigV1 config,
                                                           CallbackInfoReturnable<CompletableFuture<ExportSettings>> cir) {
        // The settings future reads the codec fields only after the dialog
        // completes, so clamping here covers every export start path.
        flashbackexportextras$sanitizeInternalExport(config);
        CompletableFuture<ExportSettings> future = cir.getReturnValue();
        com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.info(
                "Export settings request created: jobName={}, container={}, future={}",
                jobName, config.internalExport.container, future != null);
        if (future == null) {
            com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.warn(
                    "Export settings request returned null future");
            return;
        }
        future.whenComplete((settings, error) -> {
            if (error != null) {
                com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.error(
                        "Export settings future failed", error);
            } else if (settings == null) {
                com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.warn(
                        "Export settings future completed with null; export was cancelled or file dialog failed");
            } else {
                com.rethinkqaq.flashbackexportextras.FlashbackExportExtras.LOGGER.info(
                        "Export settings ready: output={}, container={}, resolution={}x{}, framerate={}",
                        settings.output(), settings.container(), settings.resolutionX(), settings.resolutionY(),
                        settings.framerate());
            }
        });
    }

    // === Format selector: injected at start of renderVideoOptions ===

    @Inject(method = "renderVideoOptions", at = @At("HEAD"), remap = false, cancellable = true)
    private static void addFormatSelector(EditorState editorState, FlashbackConfigV1 config,
                                           CallbackInfo ci) {
        // Format radio buttons
        ImGui.separator();
        ImGui.text(I18n.get("flashbackexportextras.export_format") + ":");
        ImGui.sameLine();

        boolean isExr = FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.EXR;
        if (ImGui.radioButton(I18n.get("flashbackexportextras.format_video"), !isExr)) {
            FlashbackExportExtrasConfig.INSTANCE.setExportMode(ExportMode.VIDEO);
            FlashbackExportExtrasConfig.save();
        }
        ImGui.sameLine();
        if (ImGui.radioButton(I18n.get("flashbackexportextras.format_exr"), isExr)) {
            FlashbackExportExtrasConfig.INSTANCE.setExportMode(ExportMode.EXR);
            FlashbackExportExtrasConfig.save();
        }

        if (FlashbackExportExtrasConfig.INSTANCE.getExportMode() != ExportMode.EXR) {
            // Audio directionality option for all video exports (EXR has no audio)
            boolean stereo = FlashbackExportExtrasConfig.INSTANCE.forceStereoAudio;
            if (ImGui.checkbox(I18n.get("flashbackexportextras.stereo_audio"), stereo)) {
                FlashbackExportExtrasConfig.INSTANCE.forceStereoAudio = !stereo;
                FlashbackExportExtrasConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.stereo_audio_tooltip"));
            }
            if (stereo && config.internalExport.recordAudio) {
                // The export audio is rendered through an OpenAL loopback
                // device whose layout follows stereoAudio: mono collapses
                // all spatial directionality at capture time.
                config.internalExport.stereoAudio = true;
            }
        }

        if (FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.EXR) {
            // Force container to PNG_SEQUENCE (triggers folder picker)
            config.internalExport.container =
                    com.moulberry.flashback.combo_options.VideoContainer.PNG_SEQUENCE;

            // Flashback 0.43.4's built-in depth-map export replaces the
            // startDownload call in doExport with tryDepthDownload(), which
            // bypasses this mod's entire EXR capture chain. Our EXR output
            // embeds per-frame depth already, so force the classic path.
            /*? if >=26.1 {*/
            /*config.internalExport.depthMap = false;
            *//*?}*/

            // Flashback still builds a complete ExportSettings object for a
            // PNG_SEQUENCE export before our ExportJob writer redirect runs.
            // Since EXR mode cancels Flashback's renderVideoOptions (including
            // the encoder-index reset it performs on container changes), keep
            // the codec fields in a consistent state here. The EXR writer
            // ignores the codec entirely, so always reset the encoder index —
            // index 0 is the only value guaranteed valid for whichever codec
            // the settings builder resolves for PNG_SEQUENCE.
            flashbackexportextras$sanitizeInternalExport(config);
            if (config.internalExport.selectedVideoEncoder != null
                    && config.internalExport.selectedVideoEncoder.length > 0) {
                config.internalExport.selectedVideoEncoder[0] = 0;
            }

            // Force SSAA off
            config.internalExport.ssaa = false;

            ImGui.spacing();
            ImGui.textWrapped(I18n.get("flashbackexportextras.exr_info"));

            if (!exrOutputNameInitialized) {
                String configuredName = FlashbackExportExtrasConfig.INSTANCE.exrOutputName == null
                        ? "" : FlashbackExportExtrasConfig.INSTANCE.exrOutputName.trim();
                if (configuredName.isEmpty()
                        || FlashbackExportExtrasConfig.INSTANCE.exrOutputNameAutoGenerated) {
                    configuredName = LocalDateTime.now().format(EXR_DEFAULT_NAME_FORMAT);
                    FlashbackExportExtrasConfig.INSTANCE.exrOutputName = configuredName;
                    FlashbackExportExtrasConfig.INSTANCE.exrOutputNameAutoGenerated = true;
                    FlashbackExportExtrasConfig.save();
                }
                EXR_OUTPUT_NAME.set(configuredName);
                exrOutputNameInitialized = true;
            }
            if (ImGui.inputText(I18n.get("flashbackexportextras.exr_output_name"), EXR_OUTPUT_NAME)) {
                FlashbackExportExtrasConfig.INSTANCE.exrOutputName = EXR_OUTPUT_NAME.get().trim();
                FlashbackExportExtrasConfig.INSTANCE.exrOutputNameAutoGenerated = false;
                FlashbackExportExtrasConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.exr_output_name_tooltip"));
            }

            // Depth linearization option
            boolean lin = FlashbackExportExtrasConfig.INSTANCE.depthLinearizeWorldSpace;
            if (ImGui.checkbox(I18n.get("flashbackexportextras.linearize_depth"), lin)) {
                FlashbackExportExtrasConfig.INSTANCE.depthLinearizeWorldSpace = !lin;
                FlashbackExportExtrasConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.linearize_depth_tooltip"));
            }

            FlashbackExportExtrasConfig.ExrCompression compression =
                    FlashbackExportExtrasConfig.INSTANCE.getExrCompression();
            String compressionLabel = switch (compression) {
                case ZIP -> I18n.get("flashbackexportextras.exr_compression_zip");
                case ZIPS -> I18n.get("flashbackexportextras.exr_compression_zips");
                case NONE -> I18n.get("flashbackexportextras.exr_compression_none");
            };
            if (ImGui.beginCombo(I18n.get("flashbackexportextras.exr_compression"), compressionLabel)) {
                if (ImGui.selectable(I18n.get("flashbackexportextras.exr_compression_zip"),
                        compression == FlashbackExportExtrasConfig.ExrCompression.ZIP)) {
                    FlashbackExportExtrasConfig.INSTANCE.exrCompression = FlashbackExportExtrasConfig.ExrCompression.ZIP;
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.exr_compression_zip_tooltip"));
                }
                if (ImGui.selectable(I18n.get("flashbackexportextras.exr_compression_zips"),
                        compression == FlashbackExportExtrasConfig.ExrCompression.ZIPS)) {
                    FlashbackExportExtrasConfig.INSTANCE.exrCompression = FlashbackExportExtrasConfig.ExrCompression.ZIPS;
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.exr_compression_zips_tooltip"));
                }
                if (ImGui.selectable(I18n.get("flashbackexportextras.exr_compression_none"),
                        compression == FlashbackExportExtrasConfig.ExrCompression.NONE)) {
                    FlashbackExportExtrasConfig.INSTANCE.exrCompression = FlashbackExportExtrasConfig.ExrCompression.NONE;
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.exr_compression_none_tooltip"));
                }
                ImGui.endCombo();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.exr_compression_tooltip"));
            }

            /*? if hdr {*/
            boolean sceneLinearHdrAvailable = HdrExportState.isAvailable()
                    && GpuExportBackendFactory.get().supportsSceneLinearHdr();
            if (sceneLinearHdrAvailable) {
                FlashbackExportExtrasConfig.ExrColorEncoding encoding =
                        FlashbackExportExtrasConfig.INSTANCE.getExrColorEncoding();
                String encodingLabel = switch (encoding) {
                    case SDR -> I18n.get("flashbackexportextras.exr_color_sdr");
                    case SCENE_LINEAR -> I18n.get("flashbackexportextras.exr_color_scene_linear");
                    case S_LOG3 -> I18n.get("flashbackexportextras.exr_color_slog3");
                    case ACES_CCT -> I18n.get("flashbackexportextras.exr_color_acescct");
                };
                if (ImGui.beginCombo(I18n.get("flashbackexportextras.exr_color"), encodingLabel)) {
                    for (FlashbackExportExtrasConfig.ExrColorEncoding candidate
                            : FlashbackExportExtrasConfig.ExrColorEncoding.values()) {
                        String candidateLabel = switch (candidate) {
                            case SDR -> I18n.get("flashbackexportextras.exr_color_sdr");
                            case SCENE_LINEAR -> I18n.get("flashbackexportextras.exr_color_scene_linear");
                            case S_LOG3 -> I18n.get("flashbackexportextras.exr_color_slog3");
                            case ACES_CCT -> I18n.get("flashbackexportextras.exr_color_acescct");
                        };
                        if (ImGui.selectable(candidateLabel, candidate == encoding)) {
                            FlashbackExportExtrasConfig.INSTANCE.exrColorEncoding = candidate;
                            FlashbackExportExtrasConfig.save();
                        }
                    }
                    ImGui.endCombo();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.exr_color_tooltip"));
                }
            } else if (FlashbackExportExtrasConfig.INSTANCE.getExrColorEncoding()
                    != FlashbackExportExtrasConfig.ExrColorEncoding.SDR) {
                ImGui.textWrapped(I18n.get("flashbackexportextras.exr_scene_linear_hdr_unavailable"));
            }
            /*?}*/

            // Skip normal renderVideoOptions (container dropdown, codecs, bitrate)
            ci.cancel();
            return;
        }

        /*? if hdr {*/
        // === HDR Export options (only shown when HDR Mod is available) ===
        if (HdrExportState.isAvailable() && GpuExportBackendFactory.get().supportsHdr()) {
            ExportMode currentMode = FlashbackExportExtrasConfig.INSTANCE.getExportMode();
            if (currentMode == ExportMode.HDR10 || currentMode == ExportMode.S_LOG3) {
                // The HDR video pipeline hooks the same startDownload call as
                // EXR mode; Flashback's built-in depth-map path would bypass
                // it and the export would fail its readback verification.
                /*? if >=26.1 {*/
                /*config.internalExport.depthMap = false;
                *//*?}*/
            }
            ImGui.spacing();

            // H.264 hardware encoders are 8-bit only and cannot carry 10-bit
            // HDR: block the HDR modes instead of silently exporting SDR.
            boolean h264Hardware = HdrVideoWriter.isH264HardwareEncoder(
                    flashbackexportextras$selectedEncoderName());
            if (h264Hardware && (FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.HDR10
                    || FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.S_LOG3)) {
                FlashbackExportExtrasConfig.INSTANCE.setExportMode(ExportMode.VIDEO);
                FlashbackExportExtrasConfig.save();
            }
            if (h264Hardware) {
                ImGui.textWrapped(I18n.get("flashbackexportextras.hdr_h264_hw_note"));
                ImGui.spacing();
            }

            ImGui.beginDisabled(h264Hardware);

            // HDR10 Export option
            boolean hdr10 = FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.HDR10;
            if (ImGui.checkbox(I18n.get("flashbackexportextras.hdr_export"), hdr10)) {
                FlashbackExportExtrasConfig.INSTANCE.setExportMode(hdr10 ? ExportMode.VIDEO : ExportMode.HDR10);
                FlashbackExportExtrasConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_export_tooltip"));
            }

            // S-Log3 Export option
            boolean slog3 = FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.S_LOG3;
            if (ImGui.checkbox(I18n.get("flashbackexportextras.slog3_export"), slog3)) {
                FlashbackExportExtrasConfig.INSTANCE.setExportMode(slog3 ? ExportMode.VIDEO : ExportMode.S_LOG3);
                FlashbackExportExtrasConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.slog3_export_tooltip"));
            }

            ImGui.endDisabled();

            // Shared encoder settings for HDR10 / S-Log3 video export
            ExportMode hdrMode = FlashbackExportExtrasConfig.INSTANCE.getExportMode();
            if (hdrMode == ExportMode.HDR10 || hdrMode == ExportMode.S_LOG3) {
                if (hdrMode == ExportMode.HDR10) {
                    // Peak brightness slider (PQ only — S-Log3 is scene-referred)
                    int[] peak = {FlashbackExportExtrasConfig.INSTANCE.hdrPeakBrightness};
                    if (ImGui.sliderInt(I18n.get("flashbackexportextras.hdr_peak_brightness"), peak, 500, 4000)) {
                        FlashbackExportExtrasConfig.INSTANCE.hdrPeakBrightness = peak[0];
                        HdrExportState.setPeakBrightness((float) peak[0]);
                        FlashbackExportExtrasConfig.save();
                    }
                    if (ImGui.isItemHovered()) {
                        ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_peak_brightness_tooltip"));
                    }
                }

                // FFmpeg quality preset combo
                String preset = FlashbackExportExtrasConfig.INSTANCE.getHdrQualityPreset();
                if (ImGui.beginCombo(I18n.get("flashbackexportextras.hdr_quality_preset"), preset)) {
                    for (String candidate : FlashbackExportExtrasConfig.HDR_QUALITY_PRESETS) {
                        if (ImGui.selectable(candidate, candidate.equals(preset))) {
                            FlashbackExportExtrasConfig.INSTANCE.hdrQualityPreset = candidate;
                            FlashbackExportExtrasConfig.save();
                        }
                    }
                    ImGui.endCombo();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_quality_preset_tooltip"));
                }

                // Constant-quality slider (CRF/CQ — encoders that support it)
                int[] crf = {FlashbackExportExtrasConfig.INSTANCE.hdrCrf};
                if (ImGui.sliderInt(I18n.get("flashbackexportextras.hdr_crf"), crf, 0, 51)) {
                    FlashbackExportExtrasConfig.INSTANCE.hdrCrf = crf[0];
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_crf_tooltip"));
                }

                // Encoders without CRF use Flashback's own bitrate field,
                // which renders below this block (renderVideoOptions HEAD).
                ImGui.textWrapped(I18n.get("flashbackexportextras.hdr_bitrate_hint"));

                ImGui.spacing();
                ImGui.textWrapped(I18n.get("flashbackexportextras.hdr_codec_note"));
            }
        }
        /*?}*/
    }

    /**
     * Keeps Flashback's internal codec fields consistent. Flashback's
     * settings builder resolves the effective codec against the container:
     * when the saved codec is not in {@code container.getSupportedVideoCodecs}
     * it silently falls back to that list's first entry — a codec that may
     * have far fewer encoders than the saved one. A stale
     * {@code selectedVideoEncoder} index then makes createExportSettings fail
     * with an ArrayIndexOutOfBoundsException inside its settings future (a
     * silent no-op after the folder dialog). EXR mode also cancels
     * renderVideoOptions, which is where Flashback normally resets the index,
     * so mirror the builder's codec resolution and clamp the index against it.
     */
    private static void flashbackexportextras$sanitizeInternalExport(FlashbackConfigV1 config) {
        if (config == null || config.internalExport == null) return;
        com.moulberry.flashback.configuration.FlashbackConfigV1.SubcategoryInternalExport internal =
                config.internalExport;
        if (internal.videoCodec == null) {
            internal.videoCodec = VideoCodec.H264;
        }
        VideoCodec effective = internal.videoCodec;
        if (internal.container != null) {
            try {
                VideoCodec[] supported = internal.container.getSupportedVideoCodecs(false);
                if (supported != null && supported.length > 0
                        && !java.util.Arrays.asList(supported).contains(effective)) {
                    effective = supported[0];
                }
            } catch (Throwable ignored) {
                // Fall back to clamping against the saved codec below.
            }
        }
        String[] encoders = effective.getEncoders();
        int[] selection = internal.selectedVideoEncoder;
        if (selection == null || selection.length == 0) {
            internal.selectedVideoEncoder = new int[]{0};
        } else if (encoders == null || encoders.length == 0
                || selection[0] < 0 || selection[0] >= encoders.length) {
            // Stale index (e.g. left over from a video export with a codec
            // that had more encoders) — reset to the always-valid first entry.
            selection[0] = 0;
        }
    }

    /**
     * Reads the encoder Flashback's export window currently has selected
     * ({@code internalExport.selectedVideoEncoder} indexes the selected
     * codec's encoder list), or null when no explicit encoder is chosen.
     */
    private static String flashbackexportextras$selectedEncoderName() {
        try {
            com.moulberry.flashback.configuration.FlashbackConfigV1 config =
                    com.moulberry.flashback.Flashback.getConfig();
            if (config == null || config.internalExport == null) return null;
            com.moulberry.flashback.combo_options.VideoCodec codec = config.internalExport.videoCodec;
            int[] selection = config.internalExport.selectedVideoEncoder;
            if (codec == null || selection == null || selection.length == 0) return null;
            String[] encoders = codec.getEncoders();
            int index = selection[0];
            if (encoders == null || index < 0 || index >= encoders.length) return null;
            return encoders[index];
        } catch (Throwable t) {
            return null;
        }
    }

    // === Camera path options: injected before the start/queue buttons ===

    @Inject(method = "render",
            at = @At(value = "INVOKE",
                    target = "Limgui/moulberry90/ImGui;dummy(FF)V"),
            remap = false)
    private static void addCameraPathOptions(CallbackInfo ci) {
        ImGui.separator();

        boolean exportCam = FlashbackExportExtrasConfig.INSTANCE.exportCameraPath;
        if (ImGui.checkbox(I18n.get("flashbackexportextras.export_camera_path"), exportCam)) {
            FlashbackExportExtrasConfig.INSTANCE.exportCameraPath = !exportCam;
            FlashbackExportExtrasConfig.save();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(I18n.get("flashbackexportextras.export_camera_path_tooltip"));
        }

        if (FlashbackExportExtrasConfig.INSTANCE.exportCameraPath) {
            CameraPathExporter.Format format = FlashbackExportExtrasConfig.INSTANCE.getCameraExportFormat();
            String formatLabel = switch (format) {
                case GLB -> I18n.get("flashbackexportextras.camera_format_glb");
                case USDA -> I18n.get("flashbackexportextras.camera_format_usda");
                case JSON -> I18n.get("flashbackexportextras.camera_format_json");
                case AFTER_EFFECTS_JSX -> I18n.get("flashbackexportextras.camera_format_after_effects_jsx");
                case FUSION_LUA -> I18n.get("flashbackexportextras.camera_format_fusion_lua");
            };
            if (ImGui.beginCombo(I18n.get("flashbackexportextras.camera_export_format"), formatLabel)) {
                for (CameraPathExporter.Format candidate : CameraPathExporter.Format.values()) {
                    String candidateLabel = switch (candidate) {
                        case GLB -> I18n.get("flashbackexportextras.camera_format_glb");
                        case USDA -> I18n.get("flashbackexportextras.camera_format_usda");
                        case JSON -> I18n.get("flashbackexportextras.camera_format_json");
                        case AFTER_EFFECTS_JSX -> I18n.get("flashbackexportextras.camera_format_after_effects_jsx");
                        case FUSION_LUA -> I18n.get("flashbackexportextras.camera_format_fusion_lua");
                    };
                    if (ImGui.selectable(candidateLabel, candidate == format)) {
                        FlashbackExportExtrasConfig.INSTANCE.cameraExportFormat = candidate;
                        FlashbackExportExtrasConfig.save();
                    }
                }
                ImGui.endCombo();
            }
            boolean rel = FlashbackExportExtrasConfig.INSTANCE.cameraPathRelativeOrigin;
            if (ImGui.checkbox(I18n.get("flashbackexportextras.relative_camera_path"), rel)) {
                FlashbackExportExtrasConfig.INSTANCE.cameraPathRelativeOrigin = !rel;
                FlashbackExportExtrasConfig.save();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(I18n.get("flashbackexportextras.relative_camera_path_tooltip"));
            }
        }
    }
}
