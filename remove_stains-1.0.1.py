
'''
   Copyright 2025 Lingluo Long

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,

   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
'''
from ij import ImagePlus, IJ, WindowManager
from ij.gui import GenericDialog
from ij.gui import DialogListener as IJDialogListener
from ij.process import Blitter,ImageProcessor
from ij.plugin.filter import ParticleAnalyzer, BackgroundSubtracter, GaussianBlur
from ij.plugin import LutLoader
from ij.measure import ResultsTable
from java.lang import Double
import math

#部分参考（creat mask）：https://forum.image.sc/t/image-j-jython-question/32734/3

debug_mode = False
pffc_background_display_imp = None
dialog_listener_instance = None

def remove_fixed_stains(sample_imp, flat_imp, expand_ratio=0.1, percentile=80):
    #过多的噪点会导致在减去背景时留下噪点，而在阈值处理中这些噪点则会连在一起，导致无法减去，导致计算比值时
    # 不准确（污渍区域被扩大→另一方面，减去半径值也很关键，影响是否能够消除这些噪点，或者是，阈值算法的选择也很重要，
    # 即能否自动滤除噪点，尝试后Triangle算法比Default(IsoData)和Ostu好），因此平场图像越亮越好（噪点越少）

    # # 显示原始输入图像
    # flat_imp.setTitle("1-Original Flat")
    # flat_imp.show()
    # sample_imp.setTitle("0-Original Sample")
    # sample_imp.show()

    #约定
    # imp: ImagePlus
    # ip:ImageProcessor
    # bp:ByteProcessor

    # 检查图像类型，确保只处理灰度图像
    if sample_imp.getType() == ImagePlus.COLOR_RGB or flat_imp.getType() == ImagePlus.COLOR_RGB:
        IJ.error("Grayscale images (8/16/32bit) are required for processing.")
        return None

    # 检查图像兼容性
    if not are_images_compatible(sample_imp, flat_imp):
        # 在Python中，我们可以使用IJ.showMessage来替代GenericDialog
        if not IJ.showMessageWithCancel("Image Parameters Mismatch", 
                                       "The selected images have different parameters.\n" +
                                       "Do you want to convert the flat field image to match the sample image?\n" +
                                       "This may cause signal loss."):
            return None
        
        flat_imp = convert_image_to_match(flat_imp, sample_imp)

    # 预处理平场图像
    prepared_flat_ip = prepare_flat_field(flat_imp)
    
    # 获取优化后的污渍区域mask
    stain_mask_ip = create_dirty_mask(prepared_flat_ip, percentile)
    
    # 计算校正系数
    k = calculate_correction_factor(sample_imp, prepared_flat_ip, stain_mask_ip, expand_ratio)
    
    # 执行污渍校正
    cleaned_imp = apply_correction(sample_imp, prepared_flat_ip, k)
    
    # 确保结果与sample_imp具有相同的参数
    cleaned_imp = convert_image_to_match(cleaned_imp, sample_imp)
    cleaned_imp.setTitle("Cleaned_" + sample_imp.getTitle())
    
    return cleaned_imp

def prepare_flat_field(flat_imp):
    """预处理平场图像：反相和删除背景，返回ip"""
    ip = flat_imp.getProcessor().duplicate()
    ip.invert()
    if debug_mode: ImagePlus("1.1-Inverted", ip.duplicate()).show()
    # https://imagej.net/ij/developer/api/ij/ij/plugin/filter/BackgroundSubtracter.html
    ba = BackgroundSubtracter() #不知道为什么api的BackgroundSubtracter和gui的表现不一样，可能是因为b&c参数一个重置了一个没有重置？
    #(ImageProcessor ip, double radius, boolean createBackground, boolean lightBackground, boolean useParaboloid, boolean doPresmooth, boolean correctCorners)
    # ip - 图像。输出时，它将变为减去背景的图像或背景（取决于 createBackground ）。
    # radius - 滚动球的半径，形成背景（实际上是具有相同曲率的旋转抛物面）
    # createBackground - 是否创建背景，而不是减去它。
    # lightBackground - 图像是否具有浅色背景。
    # useParaboloid - 是否使用“滑动抛物面”算法。
    # doPresmooth - 在创建背景之前，是否需要对图像进行平滑处理（3x3 平均值）。平滑处理后，背景不一定位于图像数据下方。注意gui里问的是要不要disable，这里不是是否disable
    # correctCorners - 算法是否应该尝试检测角落粒子以避免将它们作为背景减去。
    ba.rollingBallBackground(ip, ip.getWidth(), False, False, True, True, True)
    # 必须启用smooth，否则最终出现的图像会有暗角，debug对比开启前后，发现就是没有开启smooth时中间的晕影（背景）没有去掉
    #必须先invert，对于白背景去除背景的鲁棒性不好，有时候效果很差
    if debug_mode: ImagePlus("1.2-b-BackgroundSubtracted", ip.duplicate()).show()

    return ip

def create_dirty_mask(prepared_ip, percentile=80):
    """
    生成优化后的污渍mask，返回mask：8bit二值化遮罩
    Args:
        prepared_ip: ImageProcessor
        percentile: int, 0-100
    Returns:
        filtered_mask: ImageProcessor
    """
    # 复制并转换为8-bit   
    # public void setAutoThreshold​(java.lang.String method, boolean darkBackground, int lutUpdate)或​(java.lang.String method)
    # 如果要用字符串，必须只能一个参数或两个参数
    # “method”必须为“Default”、“Huang”、“Intermodes”、“IsoData”、“IJ_IsoData”、“Li”、“MaxEntropy”、
    # “Mean”、“MinError”、“Minimum”、“Moments”、“Otsu”、“Percentile”、“RenyiEntropy”、“Shanbhag”、“Triangle”或“Yen”
    # “method”字符串还可以包含关键字“dark”（深色背景）、“red”（红色查找表，默认）、
    # “b&w”（黑白查找表）、“over/under”（上下查找表）或“no-lut”（无查找表变化），例如“Huang dark b&w”。如果“method”字符串包含“no-reset”，则不会重置 16 位和 32 位图像的显示范围。
    # “lutUpdate”必须是 RED_LUT、BLACK_AND_WHITE_LUT、OVER_UNDER_LUT 或 NO_LUT_UPDATE。


    # MaxEntropy是最大熵法，目标是找到一个使前景熵 + 背景熵总和最大的阈值。当前配置下（rollingBallBackground(ip, ip.getWidth(), False, False, True, True, True)）时尝试该方法效果最好。
    # 具体尝试中，该方法只会提取出明显的颗粒的部分，而其他方法会把一些不能算是“颗粒”或者“块状”形式的、颜色比较淡的部分也提取出来，导致阈值mask范围过大。
    # 而阈值mask只是用来计算比例系数的，不是获得mask以后用masked图像相加，所以正是应该只需要那些“显著”是污渍的部分来计算比例。
    import ij.process.AutoThresholder.Method as ATM
    prepared_ip.setAutoThreshold(ATM.MaxEntropy, True, ImageProcessor.BLACK_AND_WHITE_LUT) #必须包含no-reset，因为之前去除背景没有实际应用
    #字符串调用方法没用，不知道为什么，只能用传参的方法调用
    ts_mask_bp = prepared_ip.createMask() #return ByteProcessor

    if debug_mode: ImagePlus("2.1-Thresholded Mask", ts_mask_bp.duplicate()).show()
    
    # 面积过滤（保留前80%大面积区域）
    filtered_mask_ip = filter_small_regions(ts_mask_bp, percentile)
    # filtered_mask.invert() #就应该是0黑色 255白色，应该要更新lut
    # filtered_mask.setLut(LutLoader.getLut("Grays"))
    filtered_mask_ip.setColorModel(LutLoader.getLut("Grays"))
    # getLut返回IndexColorModel，只能用setColorModel不能用setLut
    if debug_mode: ImagePlus("2.1-Small Particle Filtered Mask", filtered_mask_ip.duplicate()).show()
    # ImagePlus("3c-Filtered Mask", filtered_mask.duplicate()).show()
    
    return filtered_mask_ip

#直接用noise的Remove Outliers应该也可以，但是那个也要选radius，所以还是这个方便（？）
def filter_small_regions(mask_bp, percentile=80):
    """
    面积过滤保留指定百分比以上的区域，返回mask：8bit二值化遮罩
    Args:
        mask_bp: ByteProcessor, 二值化的mask
        percentile: int, 0-100
    Returns:
        result: ImageProcessor
    """
    #ParticleAnalyzer​(int options, int measurements, ResultsTable rt, double minSize, double maxSize, double minCirc, double maxCirc)	粒子尺寸（以像素为单位）
    rt = ResultsTable()
    pa = ParticleAnalyzer(ParticleAnalyzer.SHOW_NONE, 
                         ParticleAnalyzer.AREA, 
                         rt, 0, Double.POSITIVE_INFINITY, 0, 1)
    pa.analyze(ImagePlus("Ori", mask_bp))
    
    # 获取面积数据并去重
    areas = list(set(rt.getValue("Area", i) for i in range(rt.size())))
    if len(areas) == 0:
        IJ.log("No particles found in the mask.")
        return mask_bp
    
    # 计算面积阈值
    sorted_areas = sorted(areas)
    cutoff_index = int(len(sorted_areas) * (100 - percentile) / 100)
    
    min_area = sorted_areas[cutoff_index] if cutoff_index < len(sorted_areas) else 0
    # print(min_area)
    result_ip=ImagePlus("Cut_Mask", mask_bp.duplicate())
    
    pa = ParticleAnalyzer(ParticleAnalyzer.SHOW_MASKS, #无论如何处理完的对象都是ij.ImageStack
                         ParticleAnalyzer.AREA, 
                         rt, min_area, Double.POSITIVE_INFINITY, 0, 1)
    pa.setHideOutputImage(True)
    pa.analyze(result_ip)
    # analyze​(ImagePlus imp)对指定图像的当前切片执行粒子分析。
    # analyze​(ImagePlus imp, ImageProcessor ip)对指定的 ImagePlus 和 ImageProcessor 执行粒子分析。

    # result.duplicate().show()

    return pa.getOutputImage().getStack().getProcessor(1) #返回“轮廓”、“蒙版”、“省略号”或“计数蒙版”图像，如果在“显示：”菜单中选择了“Nothing”->show_none也是，则返回空值。

#mask:白色表示要要的，黑色表示不要的，然后转化为roi

def calculate_correction_factor(sample_imp, prepared_ip, stain_mask_ip, expand_ratio):
    """
    计算校正系数
    Args:
        sample_imp: ImagePlus
        prepared_ip: ImageProcessor
        stain_mask_ip: ImageProcessor
        expand_ratio: 0-1.0
    Returns:
        k: double
    """
    try:
        # 平场图像参数
        m_stain, m_surround = measure_regions(prepared_ip, stain_mask_ip, expand_ratio)
        
        # 样本图像参数
        sample_ip = sample_imp.getProcessor().duplicate()
        s_stain, s_surround = measure_regions(sample_ip, stain_mask_ip, expand_ratio)
        
        # 安全计算系数
        denominator = m_stain - m_surround #理论上来说，因为这个反向了，所以污渍是白色，周围是黑色，污渍减周围是正值
        if abs(denominator) < 1e-6 or math.isnan(denominator):
            return 1.0
        k = ((-1)*(s_stain-s_surround)) / denominator #污渍比周围黑，污渍减周围是副值，所以这个要乘-1
        # print("Calculated Brightness Correction Factor: "+str(k))

        return max(0, k)  # 限制系数范围
    except Exception as e:
        IJ.log("Error calculating brightness correction factor: "+str(e))
        return 1.0

def measure_regions(ip, mask_ip, expand_ratio):
    """改进的区域测量方法"""
    # 原始污渍区域
    stain_mean = masked_mean(ip, mask_ip)
    
    # 扩展区域（使用多次膨胀）
    expanded_mask_ip = expand_mask(mask_ip, expand_ratio)
    if debug_mode: ImagePlus("Expanded", expanded_mask_ip.duplicate()).show()
        # public abstract void copyBits​(ImageProcessor ip, int xloc, int yloc, int mode)
    expanded_mask_ip.copyBits(mask_ip, 0, 0, Blitter.XOR)  # 使用 Blitter 接口中定义的传输模式之一将“ip”中包含的图像复制到 (xloc, yloc)。
    if debug_mode: ImagePlus("XOR", expanded_mask_ip.duplicate()).show()

    # 有效性检查
    if expanded_mask_ip.getStats().mean == 0:
        return stain_mean, stain_mean
    
    surround_mean = masked_mean(ip, expanded_mask_ip)
    return stain_mean, surround_mean

def expand_mask(mask_ip, ratio):
    expanded_ip = mask_ip.duplicate()
    width = mask_ip.getWidth()
    steps = max(1, int(width * ratio / 10))  # 动态调整膨胀次数
    
    for _ in range(max(1, steps)):
        # print("dilate")
        expanded_ip.erode() #3x3滤波器：对象边缘理论上会在每个方向上扩展1个像素  #erode：3x3 maximum，dilate: 3x3 minimum ，官方文档可能写错了！或者源码错了，反着的！
    return expanded_ip

def masked_mean(ip, mask_ip):
    """带有效性检查的均值计算"""
    sum_val = 0
    count = 0
    m_pixels = mask_ip.getPixels()
    o_pixels = ip.convertToFloat().getPixels()
    
    for i in range(len(m_pixels)):
        if m_pixels[i] != 0:
            sum_val += o_pixels[i]
            count += 1
    # print("masked_mean", sum_val / count if count > 0 else 0.0)
    return sum_val / count if count > 0 else 0.0

def apply_correction(sample_imp, processed_fp, k):
    """带范围限制的校正应用"""
    correction = processed_fp.duplicate().convertToFloat()
    correction.multiply(k)
    
    result = sample_imp.getProcessor().duplicate().convertToFloat()
    result.copyBits(correction, 0, 0, Blitter.ADD)
    # 使用 Blitter 接口中定义的传输模式之一将“ip”中包含的图像复制到 (xloc, yloc)。
    
    # 刷新显示范围
    result.resetMinAndMax()

    # return ImagePlus("Cleaned", result)
    
    # 直接复制原始图像并替换其像素值以保留元数据
    cleaned_imp = sample_imp.duplicate()
    cleaned_imp.setProcessor(result)
    cleaned_imp.setTitle("Cleaned_RAW_" + sample_imp.getTitle())
    
    return cleaned_imp

def are_images_compatible(imp1, imp2):
    """
    检查两个图像是否兼容（尺寸、位深和类型相同）
    Args:
        imp1: ImagePlus
        imp2: ImagePlus
    Returns:
        bool: 如果图像兼容则返回True，否则返回False
    """
    return (imp1.getWidth() == imp2.getWidth() and
            imp1.getHeight() == imp2.getHeight() and
            imp1.getBitDepth() == imp2.getBitDepth() and
            imp1.getType() == imp2.getType())

def convert_image_to_match(source, target):
    """
    将源图像转换为与目标图像匹配的格式
    Args:
        source: ImagePlus, 源图像
        target: ImagePlus, 目标图像
    Returns:
        ImagePlus: 转换后的图像
    """
    source_ip = source.getProcessor()
    
    # 如果尺寸不同，进行缩放
    if source.getWidth() != target.getWidth() or source.getHeight() != target.getHeight():
        source_ip = source_ip.resize(target.getWidth(), target.getHeight())
    
    # 如果位深不同，进行转换
    if source.getBitDepth() != target.getBitDepth():
        if target.getBitDepth() == 8:
            source_ip = source_ip.convertToByte(True)
        elif target.getBitDepth() == 16:
            source_ip = source_ip.convertToShort(True)
        elif target.getBitDepth() == 32:
            source_ip = source_ip.convertToFloat()
    
    # 如果类型不同（例如RGB vs 灰度），进行转换
    if source.getType() != target.getType():
        if target.getType() == ImagePlus.COLOR_RGB:
            source_ip = source_ip.convertToRGB()
    
    # 复制目标图像并替换其像素值以保留元数据
    converted_imp = source.duplicate()
    converted_imp.setProcessor(source_ip)
    converted_imp.setTitle("Converted_" + converted_imp.getTitle())
    
    return converted_imp

def pseudo_flat_field_correction(imp_to_correct, radius, hide_background, is_in_preview=False):
    """
    对输入图像应用伪平场校正。
    这个函数会创建一个新的 ImagePlus 对象作为结果。
    Args:
        imp_to_correct (ImagePlus): 需要校正的图像。
        radius (float): 高斯模糊的半径。
        hide_pffc_background_view (bool): 是否隐藏校正过程中生成的背景预览图像。
        is_debug_and_pffc_active (bool): 标记是否在PFFC激活的调试模式下调用，用于强制显示背景。
    Returns:
        ImagePlus: 应用PFFC后的新图像。
    """
    global pffc_background_display_imp # 全局变量，用于PFFC生成的背景图像窗口

    output_imp = imp_to_correct.duplicate() # 在副本上操作
    output_imp.setTitle("PFFC_input_for_" + imp_to_correct.getTitle()) # 临时标题
    output_ip = output_imp.getProcessor()
    original_ip_reader = imp_to_correct.getProcessor() # 用于读取原始像素

    width = imp_to_correct.getWidth()
    height = imp_to_correct.getHeight()

    #1: 当前仅支持灰度图像，是否是灰度图像在remove_dirty_stains中已经判断，此处默认是
    blurred_background_source_ip = original_ip_reader.duplicate().convertToFloat()

    gb = GaussianBlur()
    blurred_background_final_ip = blurred_background_source_ip.duplicate()
    gb.blurGaussian(blurred_background_final_ip, radius, radius, 0.02)
    background_mean_intensity = blurred_background_final_ip.getStatistics().mean

    displayable_bg_ip = blurred_background_final_ip.duplicate()
    bg_title = "PFFC_Blurred_Background_" + imp_to_correct.getTitle()

    # 2: 管理PFFC背景图像的预览窗口
    if (not hide_background) or ((not is_in_preview) and debug_mode):
        # 检查现有窗口并更新，或创建新窗口
        if pffc_background_display_imp is not None and pffc_background_display_imp.getWindow() is not None and pffc_background_display_imp.isVisible():
            if pffc_background_display_imp.getTitle() != bg_title:
                pffc_background_display_imp.close() # 关闭旧的，如果标题不匹配
                pffc_background_display_imp = ImagePlus(bg_title, displayable_bg_ip)
                pffc_background_display_imp.show()
            else:
                pffc_background_display_imp.setProcessor(displayable_bg_ip)
        else: # 创建新窗口
            pffc_background_display_imp = ImagePlus(bg_title, displayable_bg_ip)
            pffc_background_display_imp.show()
        
        if pffc_background_display_imp is not None:
             pffc_background_display_imp.updateAndDraw()

    elif hide_background and pffc_background_display_imp is not None: # 如果需要隐藏且窗口存在
        if pffc_background_display_imp.getWindow() is not None and pffc_background_display_imp.isVisible():
            pffc_background_display_imp.close()
        pffc_background_display_imp = None # 清除引用

    #3: 应用校正到 output_ip，灰度图像
    for y_coord in range(height):
        for x_coord in range(width):
            bg_pixel_val = blurred_background_final_ip.getPixelValue(x_coord, y_coord)
            if bg_pixel_val == 0:
                pass
            else:
                original_pixel_val = original_ip_reader.getPixelValue(x_coord, y_coord)
                corrected_val = (original_pixel_val * background_mean_intensity) / bg_pixel_val #归一化
                output_ip.putPixelValue(x_coord, y_coord, corrected_val)
    output_ip.resetMinAndMax()

    output_imp.setProcessor(output_ip)
    output_imp.setTitle("PFFC_" + imp_to_correct.getTitle())
    return output_imp

# 用来做实时预览
class PFFCDialogUpdater(IJDialogListener):
    def __init__(self, gd, 
                 dirty_choice_idx, flat_choice_idx,
                 exp_ratio_nf_idx, percentile_nf_idx,
                 pffc_enable_cb_idx, pffc_radius_nf_idx, 
                 pffc_preview_cb_idx, pffc_hide_bg_cb_idx):
        self.gd_ref = gd 
        self.is_preview_mode_active = False

        self.dirty_choice_idx = dirty_choice_idx
        self.flat_choice_idx = flat_choice_idx
        self.exp_ratio_nf_idx = exp_ratio_nf_idx
        self.percentile_nf_idx = percentile_nf_idx
        self.pffc_enable_cb_idx = pffc_enable_cb_idx
        self.pffc_radius_nf_idx = pffc_radius_nf_idx
        self.pffc_preview_cb_idx = pffc_preview_cb_idx
        self.pffc_hide_bg_cb_idx = pffc_hide_bg_cb_idx
        
        # self.window_ids_list 不再需要缓存
        # 用于跟踪当前哪个ImagePlus被用作预览显示目标及其原始处理器
        self.current_preview_display_target_imp = None
        self.original_processor_of_display_target = None

    def dialogItemChanged(self, dialog, event):
        global pffc_background_display_imp 
        global debug_mode 

        choice_components = self.gd_ref.getChoices()
        checkbox_components = self.gd_ref.getCheckboxes()
        numeric_field_components = self.gd_ref.getNumericFields()

        # 1. 获取当前选定的图像标题，然后获取ImagePlus对象
        selected_dirty_title = choice_components.get(self.dirty_choice_idx).getSelectedItem()
        new_preview_display_target_imp = WindowManager.getImage(selected_dirty_title)

        selected_flat_title = choice_components.get(self.flat_choice_idx).getSelectedItem()
        current_flat_imp_source = WindowManager.getImage(selected_flat_title)
        
        if new_preview_display_target_imp is None or current_flat_imp_source is None:
            IJ.log("Preview Error: Unable to get currently selected image(s) for preview. Title missing or window closed.") # 日志信息更新
            if self.is_preview_mode_active and self.current_preview_display_target_imp is not None and self.original_processor_of_display_target is not None:
                try:
                    self.current_preview_display_target_imp.setProcessor(self.original_processor_of_display_target.duplicate())
                    self.current_preview_display_target_imp.updateAndDraw()
                except Exception as e:
                    IJ.log("Error restoring preview target on image fetch failure: " + str(e))
            self.current_preview_display_target_imp = None
            self.original_processor_of_display_target = None
            self.is_preview_mode_active = False
            return False # 返回False，指示对话框输入可能有问题

        exp_ratio_text = numeric_field_components.get(self.exp_ratio_nf_idx).getText()
        percentile_text = numeric_field_components.get(self.percentile_nf_idx).getText()
        pffc_should_be_enabled = checkbox_components.get(self.pffc_enable_cb_idx).getState()
        pffc_blur_radius_text = numeric_field_components.get(self.pffc_radius_nf_idx).getText()
        pffc_should_hide_background_for_preview = checkbox_components.get(self.pffc_hide_bg_cb_idx).getState()
        user_wants_preview_now = checkbox_components.get(self.pffc_preview_cb_idx).getState()

        try:
            current_exp_ratio = float(exp_ratio_text) if exp_ratio_text else 0.0
            current_percentile = int(float(percentile_text)) if percentile_text else 0
            current_pffc_radius = float(pffc_blur_radius_text) if pffc_blur_radius_text else 0.0
            if current_pffc_radius < 0.5 and pffc_should_be_enabled: 
                IJ.beep() # 轻微提示
                return False # 半径无效，阻止预览继续
        except ValueError:
            IJ.beep() # 轻微提示
            return False # 数字转换失败，阻止预览继续

        if user_wants_preview_now:
            if self.current_preview_display_target_imp is not None and \
               self.current_preview_display_target_imp is not new_preview_display_target_imp and \
               self.original_processor_of_display_target is not None:
                # IJ.log("Preview target changed: restore old target " + self.current_preview_display_target_imp.getTitle())
                try:
                    self.current_preview_display_target_imp.setProcessor(self.original_processor_of_display_target.duplicate())
                    self.current_preview_display_target_imp.updateAndDraw()
                except Exception as e:
                    IJ.log("Error restoring old preview target: " + str(e))
                self.current_preview_display_target_imp = None 
                self.original_processor_of_display_target = None

            if self.current_preview_display_target_imp is None: # 包含了目标已改变并已清理的情况
                self.current_preview_display_target_imp = new_preview_display_target_imp
                try:
                    self.original_processor_of_display_target = new_preview_display_target_imp.getProcessor().duplicate()
                    # IJ.log("Set new preview target: " + self.current_preview_display_target_imp.getTitle())
                except Exception as e: # 如果获取处理器失败
                    IJ.log("Error setting new preview target / getting processor: " + str(e))
                    self.is_preview_mode_active = False
                    return False # 无法进行预览

            self.is_preview_mode_active = True

            preview_dirty_copy_source_processor = self.original_processor_of_display_target.duplicate()
            preview_dirty_copy = ImagePlus("Preview_Calc_DirtySrc_" + self.current_preview_display_target_imp.getTitle(), preview_dirty_copy_source_processor) # 添加原始标题避免混淆
            
            preview_flat_copy = current_flat_imp_source.duplicate() 
            preview_flat_copy.setTitle("Preview_Calc_FlatSrc_" + current_flat_imp_source.getTitle())

            original_debug_state_for_preview = debug_mode 
            debug_mode = False 
            
            preview_stain_removed_imp = remove_fixed_stains(preview_dirty_copy, preview_flat_copy, 
                                                           current_exp_ratio, current_percentile)
            debug_mode = original_debug_state_for_preview

            if preview_stain_removed_imp is None:
                if self.current_preview_display_target_imp is not None and self.original_processor_of_display_target is not None:
                    self.current_preview_display_target_imp.setProcessor(self.original_processor_of_display_target.duplicate())
                    self.current_preview_display_target_imp.updateAndDraw()
                return True

            preview_final_processor_source = preview_stain_removed_imp

            if pffc_should_be_enabled:
                original_debug_state_for_preview = debug_mode 
                debug_mode = False 
                preview_pffc_applied_imp = pseudo_flat_field_correction(
                                                preview_stain_removed_imp, 
                                                current_pffc_radius, 
                                                pffc_should_hide_background_for_preview,
                                                is_in_preview=True)
                debug_mode = original_debug_state_for_preview
                preview_final_processor_source = preview_pffc_applied_imp
            
            if preview_final_processor_source is not None and self.current_preview_display_target_imp is not None:
                self.current_preview_display_target_imp.setProcessor(preview_final_processor_source.getProcessor().duplicate())
                self.current_preview_display_target_imp.updateAndDraw()
            elif self.current_preview_display_target_imp is not None and self.original_processor_of_display_target is not None: 
                self.current_preview_display_target_imp.setProcessor(self.original_processor_of_display_target.duplicate())
                self.current_preview_display_target_imp.updateAndDraw()
        else: 
            if self.is_preview_mode_active: 
                if self.current_preview_display_target_imp is not None and self.original_processor_of_display_target is not None:
                    # IJ.log("Preview Off: Restore Target " + self.current_preview_display_target_imp.getTitle())
                    try:
                        self.current_preview_display_target_imp.setProcessor(self.original_processor_of_display_target.duplicate())
                        self.current_preview_display_target_imp.updateAndDraw()
                    except Exception as e:
                        IJ.log("Error restoring target when preview turned off: " + str(e))
                
                if pffc_background_display_imp is not None and pffc_background_display_imp.isVisible():
                    pffc_background_display_imp.close()
                    pffc_background_display_imp = None
                
                self.current_preview_display_target_imp = None
                self.original_processor_of_display_target = None
                self.is_preview_mode_active = False
        return True

def run_plugin():
    global pffc_background_display_imp 
    global dialog_listener_instance 
    global debug_mode

    # active_listener 需要在 try 块外部预先定义为 None，确保 finally 中可用
    active_listener = None 

    try:
        window_ids = WindowManager.getIDList()
        if window_ids is None or len(window_ids) < 1:
            IJ.error("At least one image window needs to be open.")
            return
        
        titles = [WindowManager.getImage(window_ids[i]).getTitle() for i in range(len(window_ids))]
        
        gd = GenericDialog("Stain Removal and PFFC Plugin")

        # 控件索引定义
        dirty_choice_widget_idx = 0 
        flat_choice_widget_idx = 1  
        exp_ratio_nf_widget_idx = 0     
        percentile_nf_widget_idx = 1    
        pffc_radius_nf_widget_idx = 2   
        pffc_enable_cb_widget_idx = 0       
        keep_source_cb_widget_idx = 1       
        pffc_preview_cb_widget_idx = 2      
        pffc_hide_bg_cb_widget_idx = 3      
        debug_mode_cb_widget_idx = 4        

        gd.addChoice("Dirty image:", titles, titles[0]) 
        default_flat_idx = 1 if len(window_ids) > 1 else 0
        gd.addChoice("Flat field image:", titles, titles[default_flat_idx]) 
        gd.addNumericField("Peripheral detection expansion ratio (0-1.0):", 0.1, 2) 
        gd.addNumericField("Percentage of flat-field microparticle to be kept (0-100):", 80, 0) 
        gd.addCheckbox("PFFC (Pseudo Flat-Field Correction) after removal", False) 
        gd.addNumericField("Radius of PFFC:", 50.0, 1, 6, "Pixels") 
        gd.addCheckbox("Keep source window (for dirty image)", True) 
        gd.addCheckbox("Preview", False) 
        gd.addCheckbox("Hide PFFC background view (for preview)", True) 
        gd.addCheckbox("Debug", False) 

        dialog_listener_instance = PFFCDialogUpdater(
                                     gd, 
                                     dirty_choice_idx=dirty_choice_widget_idx,
                                     flat_choice_idx=flat_choice_widget_idx,
                                     exp_ratio_nf_idx=exp_ratio_nf_widget_idx,
                                     percentile_nf_idx=percentile_nf_widget_idx,
                                     pffc_enable_cb_idx=pffc_enable_cb_widget_idx, 
                                     pffc_radius_nf_idx=pffc_radius_nf_widget_idx,
                                     pffc_preview_cb_idx=pffc_preview_cb_widget_idx, 
                                     pffc_hide_bg_cb_idx=pffc_hide_bg_cb_widget_idx
                                     )
        active_listener = dialog_listener_instance
        gd.addDialogListener(dialog_listener_instance)

        gd.showDialog()
        
        # --- 对话框关闭后的处理 ---
        # active_listener 已经引用了 dialog_listener_instance

        if gd.wasCanceled():
            # 取消时的恢复逻辑在 finally
            # IJ.log("Plugin canceled by user.")
            return
        
        # --- 如果用户点击 OK ---
        # 恢复任何可能仍在预览状态的图像，因为主逻辑要开始了
        if active_listener is not None and active_listener.is_preview_mode_active:
            if active_listener.current_preview_display_target_imp is not None and \
               active_listener.original_processor_of_display_target is not None:
                # IJ.log("Dialog OK: Restoring last previewed image " + active_listener.current_preview_display_target_imp.getTitle() + " before final processing.")
                active_listener.current_preview_display_target_imp.setProcessor(
                    active_listener.original_processor_of_display_target.duplicate())
                active_listener.current_preview_display_target_imp.updateAndDraw()
                active_listener.current_preview_display_target_imp = None
                active_listener.original_processor_of_display_target = None
                active_listener.is_preview_mode_active = False

        final_selected_dirty_idx = gd.getNextChoiceIndex()
        final_selected_flat_idx = gd.getNextChoiceIndex()
        sample_imp = WindowManager.getImage(titles[final_selected_dirty_idx]) 
        flat_imp = WindowManager.getImage(titles[final_selected_flat_idx])

        if sample_imp is None or flat_imp is None:
            IJ.error("Error: Could not get selected Sample or Flat Field image after dialog.")
            return # finally 会执行

        expand_ratio = gd.getNextNumber()
        if gd.invalidNumber() or expand_ratio < 0 or expand_ratio > 1.0: 
            IJ.error("Invalid input for Expansion Ratio.")
            return
        
        percentile_val_raw = gd.getNextNumber()
        if gd.invalidNumber():
            IJ.error("Invalid input for Percentile.")
            return
        percentile = int(percentile_val_raw)
        if percentile < 0 or percentile > 100:
            IJ.error("Percentile must be between 0 and 100.")
            return

        pffc_selected_final = gd.getNextBoolean()
        
        pffc_radius_final = gd.getNextNumber()
        if pffc_selected_final and gd.invalidNumber():
            IJ.error("Invalid input for PFFC Radius.")
            return
        if pffc_selected_final and pffc_radius_final < 0.5:
            IJ.error("PFFC blurring radius must be >= 0.5.")
            return
        
        keep_source_window = gd.getNextBoolean()
        _ = gd.getNextBoolean() 
        pffc_hide_background_dialog_choice = gd.getNextBoolean() 
        user_selected_debug_mode = gd.getNextBoolean()
        debug_mode = user_selected_debug_mode
        
        # IJ.log("Step 1: Applying fixed stain removal...")

        stain_removed_imp = remove_fixed_stains(sample_imp, flat_imp, expand_ratio, percentile)

        if stain_removed_imp is None:
            IJ.error("Stain removal failed or was canceled by the user.")
            return

        final_result_imp = stain_removed_imp 

        if debug_mode and pffc_selected_final:
            if stain_removed_imp is not None: 
                debug_intermediate_stain_removed_display = stain_removed_imp.duplicate()
                debug_intermediate_stain_removed_display.setTitle("Cleaned_" + sample_imp.getTitle())
                debug_intermediate_stain_removed_display.show()

        if pffc_selected_final:
            # IJ.log("Step 2: Applying Pseudo Flat-Field Correction to stain-removed image...")
            pffc_applied_imp = pseudo_flat_field_correction(
                                    stain_removed_imp, 
                                    pffc_radius_final, 
                                    pffc_hide_background_dialog_choice,
                                    is_in_preview=active_listener.is_preview_mode_active
                                    )
            final_result_imp = pffc_applied_imp
        
        if final_result_imp is not None:
            final_result_imp.show()
        
        if not keep_source_window:
            if sample_imp is not final_result_imp and sample_imp is not None : # 避免关闭结果或已关闭的图像
                 if sample_imp.getWindow() is not None: # 确保窗口还存在
                    sample_imp.close()
                    # IJ.log("Closed original sample image: " + sample_imp.getTitle())

    except Exception as e:
        IJ.log("An error occurred in the plugin's main execution: " + str(e))
        import traceback
        traceback.print_exc()

    finally:
        # IJ.log("Plugin execution finished or terminated. Entering finally block for cleanup.")
        if active_listener is not None: # 确保 listener 实例存在
            if active_listener.is_preview_mode_active: # 检查标志，而不是依赖 current_preview_display_target_imp 是否为None
                if active_listener.current_preview_display_target_imp is not None and \
                   active_listener.original_processor_of_display_target is not None:
                    # IJ.log("Finally: Restoring previewed image " +
                    #        active_listener.current_preview_display_target_imp.getTitle())
                    try:
                        active_listener.current_preview_display_target_imp.setProcessor(
                            active_listener.original_processor_of_display_target.duplicate()
                        )
                        active_listener.current_preview_display_target_imp.updateAndDraw()
                    except Exception as e_restore:
                        IJ.log("Error during final restoration of previewed image: " + str(e_restore))
                else:
                    IJ.log("Error: Preview was active, but target or snapshot was None. No restoration performed for listener's target.")
            # else:
            #     IJ.log("Error: Preview was not active. No listener restoration needed.")
            
            # 清理 listener 内部状态，以防万一
            active_listener.current_preview_display_target_imp = None
            active_listener.original_processor_of_display_target = None
            active_listener.is_preview_mode_active = False # 确保标志复位

        # 清理PFFC背景窗口
        global pffc_background_display_imp
        if pffc_background_display_imp is not None and not debug_mode:
            if pffc_background_display_imp.isVisible():
                # IJ.log("Finally: Closing PFFC background display window.")
                pffc_background_display_imp.close()
            pffc_background_display_imp = None # 清除全局引用
        
        global dialog_listener_instance
        if dialog_listener_instance is not None:
            # IJ.log("Finally: Clearing global dialog_listener_instance.")
            dialog_listener_instance = None
        
        # IJ.log("Plugin cleanup finished.")

if __name__ == "__main__":
    run_plugin()