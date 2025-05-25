/*
 * Copyright 2025 Lingluo Long
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,

 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ac.loong.tools.ijpi;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.GaussianBlur;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.frame.Recorder;
import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import ij.process.AutoThresholder;
import ij.plugin.LutLoader;
import ij.plugin.PlugIn;
import java.awt.AWTEvent;
// import org.scijava.command.Command;
// import org.scijava.plugin.Plugin;

// @Plugin(type = Command.class, menuPath = "Process>Remove Dirty Stains")
public class RemoveDirtyStains implements PlugIn {
    // https://imagej.net/develop/plugin-architecture#how-can-a-plugin-specify-inputoutput-parameters
    // https://github.com/imagej/ImageJ/blob/master/ij/plugin/PlugIn.java

    private boolean debugMode = false; // Default debug mode
    private static ImagePlus pffcBackgroundDisplayImp = null; // Used for PFFC background preview

    // For DialogListener and live preview state management
    private transient ImagePlus currentPreviewDisplayTargetImp = null;
    private transient ImageProcessor originalProcessorOfDisplayTarget = null;
    private transient boolean isPreviewModeActive = false;

    @Override
    public void run(String arg) {

        int[] windowIds = WindowManager.getIDList();
        if (windowIds == null || windowIds.length < 1) { // 自己也可以减自己
            IJ.error("At least one image windows need to be open.");
            return;
        }
        String[] titles = new String[windowIds.length];
        for (int i = 0; i < windowIds.length; i++) {
            titles[i] = WindowManager.getImage(windowIds[i]).getTitle();
        }

        // Define labels for dialog components
        final String dirtyChoiceLabel = "Dirty image:";
        final String flatChoiceLabel = "Flat field image:";
        final String expandRatioLabel = "Peripheral detection expansion ratio (0-1.0):";
        final String percentileLabel = "Percentage of flat-field microparticle to be kept (0-100):";
        final String pffcEnableLabel = "PFFC (Pseudo Flat-Field Correction) after removal";
        final String pffcRadiusLabel = "Radius of PFFC (pixels):";
        final String previewLabel = "Preview";
        final String hidePffcBgLabel = "Hide PFFC background view (for preview)";
        final String debugLabel = "Debug mode";

        GenericDialog gd = new GenericDialog("Remove Dirty Stains & PFFC");
        gd.addChoice(dirtyChoiceLabel, titles, titles[0]);
        gd.addChoice(flatChoiceLabel, titles, titles.length > 1 ? titles[1] : titles[0]);
        gd.addNumericField(expandRatioLabel, 0.1, 2);
        gd.addNumericField(percentileLabel, 80, 0);
        gd.addCheckbox(pffcEnableLabel, false);
        gd.addNumericField(pffcRadiusLabel, 50.0, 1);
        gd.addCheckbox("Keep source window (for dirty image)", true);
        gd.addCheckbox("Auto-convert flat field image if parameters mismatch (size/bitdepth, etc. May cause signal loss)", true);
        gd.addCheckbox(previewLabel, false);
        gd.addCheckbox(hidePffcBgLabel, true);
        gd.addCheckbox(debugLabel, this.debugMode); // Initialize with current debugMode state

        PFFCDialogUpdater dialogUpdater = new PFFCDialogUpdater(); // Listener doesn't need params if it gets from gd
        gd.addDialogListener(dialogUpdater);

        gd.showDialog(); 

        try {
            if (gd.wasCanceled()) {
                // IJ.log("Plugin canceled by user."); 
                return;
            }
            

            if (isPreviewModeActive && currentPreviewDisplayTargetImp != null
                    && originalProcessorOfDisplayTarget != null) {
                // IJ.log("Run: OK pressed. Restoring previewed image '" + currentPreviewDisplayTargetImp.getTitle() + "' to original state before main processing.");
                try {
                    currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                    currentPreviewDisplayTargetImp.updateAndDraw();
                } catch (Exception e_restore) {
                    IJ.log("Error restoring previewed image in run() after OK, before main processing: "
                            + e_restore.getMessage());
                }
                // Reset preview state fields immediately after restoring for OK.
                resetPreviewState();
            }

            // --- User clicked OK: Get parameters and process ---
            int dirtyChoiceIdx = gd.getNextChoiceIndex();
            ImagePlus sampleImp = WindowManager.getImage(titles[dirtyChoiceIdx]);
            int flatChoiceIdx = gd.getNextChoiceIndex();
            ImagePlus flatImp = WindowManager.getImage(titles[flatChoiceIdx]);
            double expandRatio = gd.getNextNumber();
            int percentile = (int) gd.getNextNumber();
            boolean pffcEnabled = gd.getNextBoolean();
            double pffcRadius = gd.getNextNumber();
            boolean keepSourceWindow = gd.getNextBoolean();
            boolean autoConvert = gd.getNextBoolean();
            @SuppressWarnings("unused") // preview checkbox value primarily used by listener
            boolean previewDialogValue = gd.getNextBoolean();
            boolean hidePffcBackgroundViewDialog = gd.getNextBoolean();
            this.debugMode = gd.getNextBoolean();

            //可能是因为DialogListener的引入，现在macro recorder不能自动记录各个参数生成命令了，虽然还是能读取命令并执行，所以要手动构造命令参数
            // --- Manual Macro Options Recording via Recorder.recordOption ---
            if (Recorder.record && !IJ.isMacro()) {
                // Important: The keywords used here MUST be the first word of the
                // label you used when adding the component to GenericDialog, in lowercase.
                // Example: if label was "Dirty image:", keyword is "dirty".

                Recorder.recordOption("dirty", titles[dirtyChoiceIdx]);
                Recorder.recordOption("flat", titles[flatChoiceIdx]);
                Recorder.recordOption("peripheral", IJ.d2s(expandRatio, 2));
                Recorder.recordOption("percentage", Integer.toString(percentile)); // Ensure value is a string

                if (pffcEnabled) {
                    Recorder.recordOption("pffc"); // Keyword from "PFFC (Pseudo Flat-Field...)" label
                    // Keyword for radius from "Radius of PFFC (pixels):" label
                    Recorder.recordOption("radius", IJ.d2s(pffcRadius, (int) pffcRadius == pffcRadius ? 0 : 1));
                }
                if (keepSourceWindow) {
                    Recorder.recordOption("keep"); // Keyword from "Keep source window..." label
                }
                if (autoConvert) {
                    Recorder.recordOption("auto-convert"); // Keyword from "Auto-convert flat field..." label
                }
                if (previewDialogValue) {
                    Recorder.recordOption("preview"); // Keyword from "Preview" label
                }
                if (hidePffcBackgroundViewDialog) {
                    Recorder.recordOption("hide"); // Keyword from "Hide PFFC background view..." label
                }
                if (this.debugMode) {
                    Recorder.recordOption("debug"); // Keyword from "Debug mode" label
                }
            }

            // Parameter Validation
            if (expandRatio < 0 || expandRatio > 1.0) {
                IJ.error("Invalid expansion ratio. Must be between 0 and 1.0.");
                return;
            }
            if (percentile < 0 || percentile > 100) {
                IJ.error("Invalid percentile. Must be between 0 and 100.");
                return;
            }
            if (pffcEnabled && pffcRadius < 0.5) {
                IJ.error("PFFC blurring radius must be >= 0.5 pixels.");
                return;
            }
            if (sampleImp == null || flatImp == null) {
                IJ.error("Could not retrieve selected images. Please ensure they are still open.");
                return;
            }

            // Image Type and Compatibility Checks
            if (sampleImp.getType() == ImagePlus.COLOR_RGB || flatImp.getType() == ImagePlus.COLOR_RGB) {
                IJ.error("Grayscale images (8/16/32bit) are required for processing.");
                return;
            }
            if (!areImagesCompatible(sampleImp, flatImp)) {
                if (autoConvert) {
                    flatImp = convertImageToMatch(flatImp, sampleImp);
                    if (flatImp == null) {
                        IJ.error("Flat field image conversion failed or was aborted.");
                        return;
                    }
                } else {
                    IJ.error("Image parameters mismatch. Please select compatible images or enable auto-conversion.");
                    return;
                }
            }

            // --- Main Processing ---
            ImagePlus resultAfterStains = removeFixedStains(sampleImp, flatImp, expandRatio, percentile);
            ImagePlus finalResultImp = null;

            if (resultAfterStains != null) {
                finalResultImp = resultAfterStains;

                if (pffcEnabled) {
                    if (this.debugMode) {
                        ImagePlus intermediateDisplay = resultAfterStains.duplicate();
                        intermediateDisplay.setTitle("Cleaned_BeforePFFC_" + sampleImp.getTitle());
                        intermediateDisplay.show();
                    }
                    ImagePlus pffcResult = pseudoFlatFieldCorrection(resultAfterStains, pffcRadius,
                            hidePffcBackgroundViewDialog, false);
                    if (pffcResult != null) {
                        finalResultImp = pffcResult;
                        finalResultImp.setTitle("PFFC_Cleaned_" + sampleImp.getTitle());
                    } else {
                        IJ.error("PFFC processing failed. Showing only stain removal result.");
                        finalResultImp.setTitle("Cleaned_PFFC_Failed_" + sampleImp.getTitle());
                    }
                } else {
                    finalResultImp.setTitle("Cleaned_" + sampleImp.getTitle());
                }

                finalResultImp = convertImageToMatch(finalResultImp, sampleImp); // Ensure final result matches sample
                finalResultImp.show();

                if (!keepSourceWindow) {
                    if (sampleImp != finalResultImp && sampleImp.getWindow() != null) {
                        sampleImp.changes = false;
                        sampleImp.close();
                        // // IJ.log("Closed original sample image: " + sampleImp.getTitle()); //
                    }
                }
            } else {
                IJ.error("Stain removal process failed to produce a result.");
            }

        } catch (Exception e) {
            IJ.handleException(e); // Use ImageJ's built-in exception handler
            // IJ.log("An error occurred in the plugin's main execution: " + e.toString());
        } finally {
            // This block executes regardless of exceptions or normal completion (unless System.exit is called).
            // IJ.log("Plugin execution finished or terminated. Entering finally block for cleanup.");

            // Restore the previewed image if preview was active when the dialog was closed (OK or Cancel)
            if (isPreviewModeActive && currentPreviewDisplayTargetImp != null
                    && originalProcessorOfDisplayTarget != null) {
                // IJ.log("Finally: Restoring previewed image " +
                // currentPreviewDisplayTargetImp.getTitle());
                try {
                    currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                    currentPreviewDisplayTargetImp.updateAndDraw();
                } catch (Exception e_restore) {
                    IJ.log("Error during final restoration of previewed image: " + e_restore.getMessage());
                }
            }
            resetPreviewState();

            // Clean up PFFC background window if it's not meant to stay due to debug mode
            if (pffcBackgroundDisplayImp != null && pffcBackgroundDisplayImp.isVisible()) {
                if (!this.debugMode) {
                    // IJ.log("Finally: Closing PFFC background display window.");
                    pffcBackgroundDisplayImp.close();
                    pffcBackgroundDisplayImp = null;
                } else {
                    // IJ.log("Finally: PFFC background display window left open due to debug mode.");
                }
            }
            // IJ.log("Plugin cleanup finished.");
        }
    }

    private void resetPreviewState() {
        this.currentPreviewDisplayTargetImp = null;
        this.originalProcessorOfDisplayTarget = null;
        this.isPreviewModeActive = false;
        // IJ.log("Preview state has been reset.");
    }

    private ImagePlus removeFixedStains(ImagePlus sampleImp, ImagePlus flatImp, double expandRatio, int percentile) {
        ImageProcessor preparedFlat = prepareFlatField(flatImp);
        ImageProcessor stainMask = createDirtyMask(preparedFlat, percentile);
        double k = calculateCorrectionFactor(sampleImp, preparedFlat, stainMask, expandRatio);
        ImagePlus result = applyCorrection(sampleImp, preparedFlat, k);
        result.setTitle("Cleaned_" + sampleImp.getTitle());
        return result;
    }

    private ImageProcessor prepareFlatField(ImagePlus flatImp) {
        ImageProcessor ip = flatImp.getProcessor().duplicate();
        ip.invert();
        if (this.debugMode)
            new ImagePlus("Debug_1.1-Inverted", ip.duplicate()).show();

        BackgroundSubtracter ba = new BackgroundSubtracter();
        ba.rollingBallBackground(ip, ip.getWidth(), false, false, true, true, true);

        if (this.debugMode)
            new ImagePlus("Debug_1.2-BackgroundSubtracted", ip.duplicate()).show();
        return ip;
    }

    private ImageProcessor createDirtyMask(ImageProcessor preparedIp, int percentile) {
        ImageProcessor ip = preparedIp.duplicate();
        ip.setAutoThreshold(AutoThresholder.Method.MaxEntropy, true, ImageProcessor.BLACK_AND_WHITE_LUT);
        ByteProcessor maskBp = ip.createMask();
        if (this.debugMode)
            new ImagePlus("Debug_2.1-Thresholded_Mask", maskBp.duplicate()).show();

        ImageProcessor filteredMask = filterSmallRegions(maskBp, percentile);
        filteredMask.setColorModel(LutLoader.getLut("Grays"));
        if (this.debugMode)
            new ImagePlus("Debug_2.2-Small_Particle_Filtered_Mask", filteredMask.duplicate()).show();

        return filteredMask;
    }

    private ImageProcessor filterSmallRegions(ByteProcessor maskBp, int percentile) {
        ResultsTable rt = new ResultsTable();
        ParticleAnalyzer pa = new ParticleAnalyzer(ParticleAnalyzer.SHOW_NONE,
                ParticleAnalyzer.AREA,
                rt, 0, Double.POSITIVE_INFINITY, 0, 1);
        pa.analyze(new ImagePlus("", maskBp));

        // 获取面积数据
        // 使用HashSet去重
        java.util.Set<Double> uniqueAreas = new java.util.HashSet<>();
        for (int i = 0; i < rt.size(); i++) {
            uniqueAreas.add(rt.getValue("Area", i));
        }
        double[] areas = uniqueAreas.stream().mapToDouble(Double::doubleValue).toArray();

        if (areas.length == 0) {
            IJ.log("No particles found in the mask.");
            return maskBp;
        }

        // 计算面积阈值
        java.util.Arrays.sort(areas);
        int cutoffIndex = (int) (areas.length * (100 - percentile) / 100.0);
        double minArea = cutoffIndex < areas.length ? areas[cutoffIndex] : 0;
        // IJ.log("Minimum area: " + minArea);

        ImagePlus resultImp = new ImagePlus("", maskBp.duplicate());
        pa = new ParticleAnalyzer(ParticleAnalyzer.SHOW_MASKS,
                ParticleAnalyzer.AREA,
                rt, minArea, Double.POSITIVE_INFINITY, 0, 1);
        pa.setHideOutputImage(true);
        pa.analyze(resultImp);

        return pa.getOutputImage().getStack().getProcessor(1);
    }

    private double calculateCorrectionFactor(ImagePlus sampleImp, ImageProcessor preparedIp,
            ImageProcessor stainMaskIp, double expandRatio) {
        try {
            // 平场图像参数
            double[] flatMeasures = measureRegions(preparedIp, stainMaskIp, expandRatio);
            double mStain = flatMeasures[0];
            double mSurround = flatMeasures[1];

            // 样本图像参数
            ImageProcessor sampleIp = sampleImp.getProcessor().duplicate();
            double[] sampleMeasures = measureRegions(sampleIp, stainMaskIp, expandRatio);
            double sStain = sampleMeasures[0];
            double sSurround = sampleMeasures[1];

            // 安全计算系数
            double denominator = mStain - mSurround;
            if (Math.abs(denominator) < 1e-6 || Double.isNaN(denominator)) {
                // IJ.log("Warning: Correction factor denominator near zero or NaN. Defaulting k to 1.0.");
                return 1.0;
            }

            double k = (-1) * (sStain - sSurround) / denominator;
            // IJ.log("Brightness correction factor: " + k);
            
            return Math.max(0, k);
        } catch (Exception e) {
            IJ.log("Error calculating brightness correction factor: " + e.getMessage());
            e.printStackTrace();
            return 1.0;
        }
    }

    private double[] measureRegions(ImageProcessor ip, ImageProcessor maskIp, double expandRatio) {
        // 原始污渍区域
        double stainMean = maskedMean(ip, maskIp);

        // 扩展区域
        ImageProcessor expandedMaskIp = expandMask(maskIp, expandRatio);
        if (this.debugMode)
            new ImagePlus("Debug_Expanded_Mask_in_Measure", expandedMaskIp.duplicate()).show();

        expandedMaskIp.copyBits(maskIp, 0, 0, Blitter.XOR);
        if (this.debugMode)
            new ImagePlus("Debug_XOR_Ring_Mask_in_Measure", expandedMaskIp.duplicate()).show();

        // 有效性检查
        if (expandedMaskIp.getStatistics().mean == 0) {
            // IJ.log("Warning: Surrounding ring mask is empty in measureRegions. Using stainMean for surroundMean.");
            return new double[] { stainMean, stainMean };
        }

        double surroundMean = maskedMean(ip, expandedMaskIp);
        return new double[] { stainMean, surroundMean };
    }

    private ImageProcessor expandMask(ImageProcessor maskIp, double ratio) {
        ImageProcessor expandedIp = maskIp.duplicate();
        int width = maskIp.getWidth();
        int steps = Math.max(1, (int)(width * ratio / 10));
        
        for (int i = 0; i < steps; i++) {
            expandedIp.erode();
        }
        return expandedIp;
    }

    private double maskedMean(ImageProcessor ip, ImageProcessor maskIp) {
        double sum = 0;
        int count = 0;
        byte[] maskPixels = (byte[]) maskIp.getPixels(); //mask:0-255->byte
        float[] origPixels = (float[]) ip.convertToFloat().getPixels(); //float:32bit

        for (int i = 0; i < maskPixels.length; i++) {
            if (maskPixels[i] != 0) {
                sum += origPixels[i];
                count++;
            }
        }
        // IJ.log("masked_mean: " + String.valueOf(count > 0 ? sum / count : 0.0));
        return count > 0 ? sum / count : 0.0;
    }

    private ImagePlus applyCorrection(ImagePlus sampleImp, ImageProcessor processedFp, double k) {
        ImageProcessor correction = processedFp.duplicate().convertToFloat();
        correction.multiply(k);

        ImageProcessor result = sampleImp.getProcessor().duplicate().convertToFloat();
        result.copyBits(correction, 0, 0, Blitter.ADD);
        result.resetMinAndMax();

        // 直接复制原始图像并替换其像素值以保留元数据
        ImagePlus cleanedImp = sampleImp.duplicate();
        cleanedImp.setProcessor(result);

        cleanedImp.setTitle("Cleaned_RAW_" + sampleImp.getTitle());

        return cleanedImp;
    }

    private boolean areImagesCompatible(ImagePlus imp1, ImagePlus imp2) {
        return imp1.getWidth() == imp2.getWidth() &&
                imp1.getHeight() == imp2.getHeight() &&
                imp1.getBitDepth() == imp2.getBitDepth() &&
                imp1.getType() == imp2.getType();
    }

    private ImagePlus convertImageToMatch(ImagePlus source, ImagePlus target) {
        ImageProcessor sourceIp = source.getProcessor();
        // ImageProcessor targetIp = target.getProcessor();
        
        // 如果尺寸不同，进行缩放
        if (source.getWidth() != target.getWidth() || source.getHeight() != target.getHeight()) {
            sourceIp = sourceIp.resize(target.getWidth(), target.getHeight());
        }
        
        // 如果位深不同，进行转换
        if (source.getBitDepth() != target.getBitDepth()) {
            switch (target.getBitDepth()) {
                case 8:
                    sourceIp = sourceIp.convertToByte(true);
                    break;
                case 16:
                    sourceIp = sourceIp.convertToShort(true);
                    break;
                case 32:
                    sourceIp = sourceIp.convertToFloat();
                    break;
            }
        }
        
        // 如果类型不同（例如RGB vs 灰度），进行转换
        if (source.getType() != target.getType()) {
            if (target.getType() == ImagePlus.COLOR_RGB) {
                sourceIp = sourceIp.convertToRGB();
            }
        }

        // return new ImagePlus("Converted", sourceIp);
        // 复制目标图像并替换其像素值以保留元数据
        ImagePlus convertedImp = source.duplicate();
        convertedImp.setProcessor(sourceIp);
        convertedImp.setTitle(source.getTitle());

        return convertedImp;
    }


    private ImagePlus pseudoFlatFieldCorrection(ImagePlus impToCorrect, double radius, boolean hideBackgroundPreview,
            boolean isInPreviewMode) {
        if (impToCorrect == null) {
            IJ.log("PFFC Error: Input image is null.");
            return null;
        }
        if (radius < 0.5) {
            // IJ.log("PFFC Warning: Radius is too small (<0.5), PFFC might not be effective. Using 0.5.");
            radius = 0.5;
        }

        ImagePlus outputImp = impToCorrect.duplicate();
        outputImp.setTitle("PFFC_Applied_to_" + impToCorrect.getTitle());
        ImageProcessor outputIp = outputImp.getProcessor();
        ImageProcessor originalIpReader = impToCorrect.getProcessor();

        ImageProcessor blurredBackgroundSourceIp = originalIpReader.duplicate().convertToFloat();
        GaussianBlur gb = new GaussianBlur();
        gb.blurGaussian(blurredBackgroundSourceIp, radius, radius, 0.02);

        double backgroundMeanIntensity = blurredBackgroundSourceIp.getStatistics().mean;
        if (Double.isNaN(backgroundMeanIntensity) || backgroundMeanIntensity == 0) {
            // IJ.log("PFFC Warning: Mean intensity of blurred background is zero or NaN.");
            if (backgroundMeanIntensity == 0)
                return outputImp.duplicate();
        }

        String bgTitle = "PFFC_Blurred_Background_" + impToCorrect.getTitle();
        if ((!hideBackgroundPreview && isInPreviewMode) || (this.debugMode && !isInPreviewMode)
                || (!isInPreviewMode && !hideBackgroundPreview && !this.debugMode)) {
            if (pffcBackgroundDisplayImp != null && pffcBackgroundDisplayImp.getWindow() != null
                    && pffcBackgroundDisplayImp.isVisible()) {
                if (!pffcBackgroundDisplayImp.getTitle().equals(bgTitle)) {
                    pffcBackgroundDisplayImp.close();
                    pffcBackgroundDisplayImp = new ImagePlus(bgTitle, blurredBackgroundSourceIp.duplicate());
                    pffcBackgroundDisplayImp.show();
                } else {
                    pffcBackgroundDisplayImp.setProcessor(blurredBackgroundSourceIp.duplicate());
                }
            } else {
                pffcBackgroundDisplayImp = new ImagePlus(bgTitle, blurredBackgroundSourceIp.duplicate());
                pffcBackgroundDisplayImp.show();
            }
            if (pffcBackgroundDisplayImp != null)
                pffcBackgroundDisplayImp.updateAndDraw();
        } else if (pffcBackgroundDisplayImp != null && pffcBackgroundDisplayImp.isVisible()) {
            if (isInPreviewMode && hideBackgroundPreview) {
                pffcBackgroundDisplayImp.close();
                pffcBackgroundDisplayImp = null;
            } else if (!this.debugMode && !isInPreviewMode) {
                pffcBackgroundDisplayImp.close();
                pffcBackgroundDisplayImp = null;
            }
        }

        ImageProcessor floatOutputIp = outputIp.convertToFloat();
        float[] pixels = (float[]) floatOutputIp.getPixels();
        float[] blurredPixels = (float[]) blurredBackgroundSourceIp.getPixels();

        for (int i = 0; i < pixels.length; i++) {
            double bgPixelVal = blurredPixels[i];
            if (bgPixelVal != 0 && !Double.isNaN(bgPixelVal)) {
                double originalPixelVal = originalIpReader.getf(i);
                double correctedVal = (originalPixelVal * backgroundMeanIntensity) / bgPixelVal;
                pixels[i] = (float) correctedVal;
            }
        }
        outputIp.setPixels(pixels);
        floatOutputIp.resetMinAndMax();
        outputImp.setProcessor(floatOutputIp);

        return outputImp;
    }

    private class PFFCDialogUpdater implements DialogListener {
        @Override
        public boolean dialogItemChanged(GenericDialog gd, AWTEvent e) {
            // IJ.log("-----------------------------------------------------");
            // IJ.log("DialogItemChanged triggered. Event: " + (e != null ? e.getSource().getClass().getName() : "null"));
            java.util.Vector choices = gd.getChoices();
            java.util.Vector numerics = gd.getNumericFields();
            java.util.Vector checkboxes = gd.getCheckboxes();

            if (choices == null || choices.size() < 2 || numerics == null || numerics.size() < 3 || checkboxes == null
                    || checkboxes.size() < 6) {
                // IJ.log("Dialog components not fully initialized yet or mismatched count.");
                return true;
            }

            // --- 1. Get current parameters from the dialog ---
            String selectedDirtyTitle = ((java.awt.Choice) choices.get(0)).getSelectedItem();
            ImagePlus newSelectedDirtyImp = WindowManager.getImage(selectedDirtyTitle);

            String selectedFlatTitle = ((java.awt.Choice) choices.get(1)).getSelectedItem();
            ImagePlus currentFlatImpSource = WindowManager.getImage(selectedFlatTitle);

            double expRatio = parseDouble(((java.awt.TextField) numerics.get(0)).getText(), 0.1);
            int perc = parseInt(((java.awt.TextField) numerics.get(1)).getText(), 80);
            double pffcRad = parseDouble(((java.awt.TextField) numerics.get(2)).getText(), 50.0);

            boolean pffcSelected = ((java.awt.Checkbox) checkboxes.get(0)).getState();
            boolean userWantsPreviewNow = ((java.awt.Checkbox) checkboxes.get(3)).getState();
            boolean hidePffcBG = ((java.awt.Checkbox) checkboxes.get(4)).getState();
            RemoveDirtyStains.this.debugMode = ((java.awt.Checkbox) checkboxes.get(5)).getState();

            // IJ.log("Params: Preview=" + userWantsPreviewNow + ", PFFC=" + pffcSelected +
            // ", HideBG=" + hidePffcBG + ", Radius=" + pffcRad + ", ClassDebug=" +
            // RemoveDirtyStains.this.debugMode);
            // IJ.log("Selected Dirty: " + (newSelectedDirtyImp != null ?
            // newSelectedDirtyImp.getTitle() : "null") +
            // ", Selected Flat: " + (currentFlatImpSource != null ?
            // currentFlatImpSource.getTitle() : "null"));

            if (newSelectedDirtyImp == null || currentFlatImpSource == null) {
                // IJ.log("Preview Error: Dirty or Flat image not found (null).");
                if (isPreviewModeActive && currentPreviewDisplayTargetImp != null
                        && originalProcessorOfDisplayTarget != null) {
                    // IJ.log("Restoring original due to null selected image during active preview.");
                    currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                    currentPreviewDisplayTargetImp.updateAndDraw();
                }
                resetPreviewState();
                return true; // Allow dialog to continue, but preview state is reset
            }

            boolean paramsValid = true;
            try {
                if (Double.isNaN(expRatio) || expRatio < 0 || expRatio > 1.0)
                    paramsValid = false;
                if (perc < 0 || perc > 100)
                    paramsValid = false;
                if (pffcSelected && (Double.isNaN(pffcRad) || pffcRad < 0.5))
                    paramsValid = false;
            } catch (Exception ex) {
                paramsValid = false;
            }

            if (!paramsValid) {
                IJ.beep();
                // IJ.log("Parameter validation failed for preview update.");
                return true;
            }

            // --- Main Preview Logic ---
            if (userWantsPreviewNow) {
                // IJ.log("Preview is ON.");
                if (currentPreviewDisplayTargetImp != newSelectedDirtyImp) {
                    // IJ.log("Preview target image has changed from " +
                    // (currentPreviewDisplayTargetImp != null ?
                    // currentPreviewDisplayTargetImp.getTitle() : "null") + " to " +
                    // newSelectedDirtyImp.getTitle());
                    if (currentPreviewDisplayTargetImp != null && originalProcessorOfDisplayTarget != null
                            && isPreviewModeActive) {
                        // IJ.log("Restoring previously previewed image: " + currentPreviewDisplayTargetImp.getTitle());
                        currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                        currentPreviewDisplayTargetImp.updateAndDraw();
                    }
                    currentPreviewDisplayTargetImp = newSelectedDirtyImp;
                    originalProcessorOfDisplayTarget = newSelectedDirtyImp.getProcessor().duplicate();
                    // IJ.log("Stored original processor for new target: " + currentPreviewDisplayTargetImp.getTitle());
                } else if (originalProcessorOfDisplayTarget == null && currentPreviewDisplayTargetImp != null) {
                    originalProcessorOfDisplayTarget = currentPreviewDisplayTargetImp.getProcessor().duplicate();
                    // IJ.log("Preview toggled ON for current target. Stored original processor for:
                    // " + currentPreviewDisplayTargetImp.getTitle());
                }

                if (currentPreviewDisplayTargetImp == null || originalProcessorOfDisplayTarget == null) {
                    // IJ.log("Error: currentPreviewDisplayTargetImp or
                    // originalProcessorOfDisplayTarget is null. Cannot proceed with preview.");
                    resetPreviewState();
                    return true;
                }
                isPreviewModeActive = true;

                ImagePlus previewDirtyImgForProcessing = currentPreviewDisplayTargetImp.duplicate();
                previewDirtyImgForProcessing.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                // IJ.log("Created fresh 'previewDirtyImgForProcessing' from original processor
                // for: " + currentPreviewDisplayTargetImp.getTitle());

                ImagePlus previewFlatCopy = currentFlatImpSource.duplicate();
                // IJ.log("Duplicated flat field image for processing: " +
                // previewFlatCopy.getTitle());

                if (!areImagesCompatible(previewDirtyImgForProcessing, previewFlatCopy)) {
                    // IJ.log("Images for preview processing are not compatible. Attempting to
                    // convert flat image...");
                    previewFlatCopy = convertImageToMatch(previewFlatCopy, previewDirtyImgForProcessing);
                    if (previewFlatCopy == null) {
                        // IJ.log("Preview: Flat conversion failed.");
                        restoreAndClearPreview();
                        return true;
                    }
                    // IJ.log("Flat image converted for preview compatibility.");
                }
                if (previewDirtyImgForProcessing.getType() == ImagePlus.COLOR_RGB
                        || previewFlatCopy.getType() == ImagePlus.COLOR_RGB) {
                    // IJ.log("Preview: Grayscale images required.");
                    restoreAndClearPreview();
                    return true;
                }

                boolean actualDebugCheckboxState = RemoveDirtyStains.this.debugMode;
                RemoveDirtyStains.this.debugMode = false;
                // IJ.log("Temporarily set class debugMode to false. Actual checkbox state was:
                // " + actualDebugCheckboxState);

                ImagePlus stainRemovedPreview = null;
                ImagePlus finalImageForDisplay = null;

                try {
                    // --- 1: Always perform stain removal for preview ---
                    // IJ.log("Performing stain removal for preview...");
                    stainRemovedPreview = removeFixedStains(previewDirtyImgForProcessing, previewFlatCopy, expRatio,
                            perc);

                    if (stainRemovedPreview == null) {
                        // IJ.log("Stain removal returned null for preview.");
                    } else {
                        // IJ.log("Stain removal successful for preview. Result: " + stainRemovedPreview.getTitle());
                        // --- 2: Perform PFFC if selected ---
                        if (pffcSelected) {
                            // IJ.log("PFFC is selected. Performing PFFC on stain-removed image. Radius: " +
                            // pffcRad + ", HideBG: " + hidePffcBG);
                            ImagePlus pffcPreviewResult = pseudoFlatFieldCorrection(stainRemovedPreview, pffcRad,
                                    hidePffcBG, true); // isInPreviewMode = true
                            if (pffcPreviewResult != null) {
                                finalImageForDisplay = pffcPreviewResult;
                                // IJ.log("PFFC successful for preview. Result: " +
                                // pffcPreviewResult.getTitle());
                            } else {
                                // IJ.log("PFFC processing returned null for preview. Using only stain-removed
                                // result.");
                                finalImageForDisplay = stainRemovedPreview;
                            }
                        } else {
                            // IJ.log("PFFC is NOT selected for preview. Using stain-removed result.");
                            finalImageForDisplay = stainRemovedPreview;
                            if (pffcBackgroundDisplayImp != null && pffcBackgroundDisplayImp.isVisible()) {
                                // IJ.log("Closing existing PFFC background window as PFFC is now deselected.");
                                pffcBackgroundDisplayImp.close();
                                pffcBackgroundDisplayImp = null;
                            }
                        }
                    }
                } finally {
                    RemoveDirtyStains.this.debugMode = actualDebugCheckboxState;
                    // IJ.log("Restored class debugMode to: " + RemoveDirtyStains.this.debugMode);
                }

                // --- 3: Update the main preview display window ---
                if (finalImageForDisplay != null && currentPreviewDisplayTargetImp != null) {
                    // IJ.log("Updating main preview display with " +
                    // finalImageForDisplay.getTitle());
                    ImageProcessor finalProcessorForDisplay = convertImageToMatch(finalImageForDisplay,
                            currentPreviewDisplayTargetImp).getProcessor();
                    currentPreviewDisplayTargetImp.setProcessor(finalProcessorForDisplay.duplicate());
                    currentPreviewDisplayTargetImp.updateAndDraw();
                    // IJ.log("Main preview display updated.");
                } else if (currentPreviewDisplayTargetImp != null && originalProcessorOfDisplayTarget != null) {
                    // IJ.log("Preview processing resulted in null image or error, restoring
                    // original processor to display target: " +
                    // currentPreviewDisplayTargetImp.getTitle());
                    currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                    currentPreviewDisplayTargetImp.updateAndDraw();
                } else {
                    // IJ.log("Cannot update display: finalImageForDisplay is null or
                    // currentPreviewDisplayTargetImp is null, and no original to restore.");
                }

            } else {
                // IJ.log("Preview is OFF.");
                if (isPreviewModeActive && currentPreviewDisplayTargetImp != null
                        && originalProcessorOfDisplayTarget != null) {
                    // IJ.log("Restoring original image because preview was turned off: " +
                    // currentPreviewDisplayTargetImp.getTitle());
                    currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                    currentPreviewDisplayTargetImp.updateAndDraw();

                    if (pffcBackgroundDisplayImp != null && pffcBackgroundDisplayImp.isVisible()) {
                        // IJ.log("Closing PFFC background window because preview was turned off.");
                        pffcBackgroundDisplayImp.close();
                        pffcBackgroundDisplayImp = null;
                    }
                }
                resetPreviewState();
            }
            return true;
        }

        private void restoreAndClearPreview() {
            // IJ.log("restoreAndClearPreview called.");
            if (currentPreviewDisplayTargetImp != null && originalProcessorOfDisplayTarget != null) {
                // IJ.log("Restoring original processor to " +
                // currentPreviewDisplayTargetImp.getTitle() + " in restoreAndClearPreview.");
                currentPreviewDisplayTargetImp.setProcessor(originalProcessorOfDisplayTarget.duplicate());
                currentPreviewDisplayTargetImp.updateAndDraw();
            }
            if (pffcBackgroundDisplayImp != null && pffcBackgroundDisplayImp.isVisible() && isPreviewModeActive) {
                // IJ.log("Closing PFFC background window during restoreAndClearPreview.");
                pffcBackgroundDisplayImp.close();
                pffcBackgroundDisplayImp = null;
            }
            resetPreviewState();
        }

        private double parseDouble(String s, double defaultValue) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }

        private int parseInt(String s, int defaultValue) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }
}
