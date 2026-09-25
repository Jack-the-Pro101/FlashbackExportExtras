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

        if (FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.EXR) {
            // Force container to PNG_SEQUENCE (triggers folder picker)
            config.internalExport.container =
                    com.moulberry.flashback.combo_options.VideoContainer.PNG_SEQUENCE;

            // Flashback still builds a complete ExportSettings object for a
            // PNG_SEQUENCE export before our ExportJob writer redirect runs.
            // Since EXR mode skips Flashback's normal codec controls, the
            // codec fields may otherwise remain null and createExportSettings
            // fails before the ExportJob is queued.
            if (config.internalExport.videoCodec == null) {
                config.internalExport.videoCodec = VideoCodec.H264;
            }
            if (config.internalExport.selectedVideoEncoder == null
                    || config.internalExport.selectedVideoEncoder.length == 0) {
                config.internalExport.selectedVideoEncoder = new int[]{0};
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
                if (ImGui.selectable(I18n.get("flashbackexportextras.exr_compression_zips"),
                        compression == FlashbackExportExtrasConfig.ExrCompression.ZIPS)) {
                    FlashbackExportExtrasConfig.INSTANCE.exrCompression = FlashbackExportExtrasConfig.ExrCompression.ZIPS;
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.selectable(I18n.get("flashbackexportextras.exr_compression_none"),
                        compression == FlashbackExportExtrasConfig.ExrCompression.NONE)) {
                    FlashbackExportExtrasConfig.INSTANCE.exrCompression = FlashbackExportExtrasConfig.ExrCompression.NONE;
                    FlashbackExportExtrasConfig.save();
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
                };
                if (ImGui.beginCombo(I18n.get("flashbackexportextras.exr_color"), encodingLabel)) {
                    for (FlashbackExportExtrasConfig.ExrColorEncoding candidate
                            : FlashbackExportExtrasConfig.ExrColorEncoding.values()) {
                        String candidateLabel = switch (candidate) {
                            case SDR -> I18n.get("flashbackexportextras.exr_color_sdr");
                            case SCENE_LINEAR -> I18n.get("flashbackexportextras.exr_color_scene_linear");
                            case S_LOG3 -> I18n.get("flashbackexportextras.exr_color_slog3");
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
            ImGui.spacing();
            
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

            // Settings for HDR10 mode
            if (FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.HDR10) {
                // Peak brightness slider
                int[] peak = {FlashbackExportExtrasConfig.INSTANCE.hdrPeakBrightness};
                if (ImGui.sliderInt(I18n.get("flashbackexportextras.hdr_peak_brightness"), peak, 500, 4000)) {
                    FlashbackExportExtrasConfig.INSTANCE.hdrPeakBrightness = peak[0];
                    HdrExportState.setPeakBrightness((float) peak[0]);
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_peak_brightness_tooltip"));
                }

                // Paper white brightness slider
                int[] paperWhite = {FlashbackExportExtrasConfig.INSTANCE.hdrPaperWhiteNits};
                if (ImGui.sliderInt(I18n.get("flashbackexportextras.hdr_paper_white"), paperWhite, 80, 500)) {
                    FlashbackExportExtrasConfig.INSTANCE.hdrPaperWhiteNits = paperWhite[0];
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_paper_white_tooltip"));
                }
            }

            // Settings for S-Log3 mode
            if (FlashbackExportExtrasConfig.INSTANCE.getExportMode() == ExportMode.S_LOG3) {
                // Peak brightness slider
                int[] peak = {FlashbackExportExtrasConfig.INSTANCE.hdrPeakBrightness};
                if (ImGui.sliderInt(I18n.get("flashbackexportextras.hdr_peak_brightness"), peak, 500, 4000)) {
                    FlashbackExportExtrasConfig.INSTANCE.hdrPeakBrightness = peak[0];
                    HdrExportState.setPeakBrightness((float) peak[0]);
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_peak_brightness_tooltip"));
                }

                // Paper white brightness slider
                int[] paperWhite = {FlashbackExportExtrasConfig.INSTANCE.hdrPaperWhiteNits};
                if (ImGui.sliderInt(I18n.get("flashbackexportextras.hdr_paper_white"), paperWhite, 80, 500)) {
                    FlashbackExportExtrasConfig.INSTANCE.hdrPaperWhiteNits = paperWhite[0];
                    FlashbackExportExtrasConfig.save();
                }
                if (ImGui.isItemHovered()) {
                    ImGui.setTooltip(I18n.get("flashbackexportextras.hdr_paper_white_tooltip"));
                }
            }
        }
        /*?}*/
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
